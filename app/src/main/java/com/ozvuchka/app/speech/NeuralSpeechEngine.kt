package com.ozvuchka.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

enum class SpeechPhase {
    MODEL_MISSING,
    DOWNLOADING,
    EXTRACTING,
    READY,
    LOADING,
    SPEAKING,
    PAUSED,
    ERROR,
}

data class SpeechStatus(
    val phase: SpeechPhase,
    val message: String? = null,
    val completed: Long = 0,
    val total: Long = 0,
    val currentText: String? = null,
    val textOffset: Int = 0,
    val textLength: Int = 0,
)

/**
 * Local neural speech powered by the official sherpa-onnx Android AAR and Supertonic 3.
 *
 * [installModel] downloads the model once into app-private storage. [speak] never uses
 * the network. Calls return immediately; model work, inference and AudioTrack writes run
 * on one worker thread. Listener notifications are posted to the main thread.
 *
 * The host app should call [close] when disposing this engine. For playback while the
 * activity is gone, own this instance from a MediaSessionService.
 */
class NeuralSpeechEngine(
    context: Context,
    private val preferFullModel: () -> Boolean = { true },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val modelStore = SpeechModelStore(appContext.filesDir)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-neural-speech").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    private val speechEpoch = AtomicLong(0)
    private val installEpoch = AtomicLong(0)
    private val pauseRequested = AtomicBoolean(false)
    private val pauseLock = java.lang.Object()

    @Volatile private var synthesizer: OfflineTts? = null
    private var synthesizerDirectory: String? = null
    @Volatile private var audioTrack: AudioTrack? = null
    @Volatile var status: SpeechStatus = SpeechStatus(
        if (modelStore.isInstalled()) SpeechPhase.READY else SpeechPhase.MODEL_MISSING,
    )
        private set

    /** Set or replace this from the main thread. */
    @Volatile var onStatus: ((SpeechStatus) -> Unit)? = null

    fun isModelInstalled(): Boolean = modelStore.isInstalled()
    fun isFullModelInstalled(): Boolean = modelStore.isFullInstalled()

    /** Start the explicit, one-time model download. [cancelInstall] cancels it. */
    fun installModel() {
        if (closed.get()) return
        val request = installEpoch.incrementAndGet()
        executor.execute {
            if (closed.get() || installEpoch.get() != request) return@execute
            if (modelStore.isInstalled()) {
                publish(SpeechStatus(SpeechPhase.READY))
                return@execute
            }
            try {
                modelStore.install(
                    cancelled = { installEpoch.get() != request || closed.get() },
                    onProgress = { progress ->
                        val phase = when (progress.stage) {
                            ModelInstallStage.DOWNLOAD -> SpeechPhase.DOWNLOADING
                            ModelInstallStage.EXTRACT -> SpeechPhase.EXTRACTING
                        }
                        publish(
                            SpeechStatus(
                                phase = phase,
                                message = progress.currentFile,
                                completed = progress.completed,
                                total = progress.total,
                            )
                        )
                    },
                )
                publish(SpeechStatus(SpeechPhase.READY))
            } catch (_: ModelInstallCancelledException) {
                publish(SpeechStatus(SpeechPhase.MODEL_MISSING, "Загрузка модели отменена"))
            } catch (error: Exception) {
                publish(SpeechStatus(SpeechPhase.ERROR, "Не удалось загрузить модель: ${error.message}"))
            }
        }
    }

    fun installFullModel() {
        if (closed.get()) return
        val request = installEpoch.incrementAndGet()
        executor.execute {
            if (closed.get() || installEpoch.get() != request) return@execute
            if (modelStore.isFullInstalled()) {
                publish(SpeechStatus(SpeechPhase.READY))
                return@execute
            }
            try {
                modelStore.installFull(
                    cancelled = { installEpoch.get() != request || closed.get() },
                    onProgress = { progress ->
                        publish(SpeechStatus(SpeechPhase.DOWNLOADING, "Полноточная модель: ${progress.currentFile}", progress.completed, progress.total))
                    },
                )
                synthesizer?.release()
                synthesizer = null
                synthesizerDirectory = null
                publish(SpeechStatus(SpeechPhase.READY, "Полноточная модель установлена"))
            } catch (_: ModelInstallCancelledException) {
                publish(SpeechStatus(SpeechPhase.READY, "Улучшение голоса отменено"))
            } catch (error: Exception) {
                publish(SpeechStatus(SpeechPhase.ERROR, "Не удалось установить модель высокого качества: ${error.message}"))
            }
        }
    }

    fun cancelInstall() {
        installEpoch.incrementAndGet()
        modelStore.interruptDownload()
    }

    /**
     * Synthesize and play text with the installed model. The default Russian voice is
     * selected with [language] = "ru"; use "en" for English. Voice IDs are model presets.
     * Work is queued after any ongoing installation. No audio is uploaded or cached.
     */
    fun speak(text: String, language: String = "ru", voiceId: Int = 0, speed: Float = 1.0f) {
        if (closed.get()) return
        val request = speechEpoch.incrementAndGet()
        pauseRequested.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        executor.execute {
            if (speechStopped(request)) return@execute
            if (!modelStore.isInstalled()) {
                publish(SpeechStatus(SpeechPhase.MODEL_MISSING, "Сначала загрузите голосовую модель"))
                return@execute
            }
            if (text.isBlank()) return@execute
            if (language !in SUPPORTED_LANGUAGES || voiceId < 0 || speed !in 0.5f..2.0f) {
                publish(SpeechStatus(SpeechPhase.ERROR, "Некорректные параметры озвучки"))
                return@execute
            }

            var track: AudioTrack? = null
            try {
                publish(SpeechStatus(SpeechPhase.LOADING))
                val directory = modelStore.modelDirectory(preferFullModel())
                if (synthesizerDirectory != directory.absolutePath) {
                    synthesizer?.release()
                    synthesizer = null
                    synthesizerDirectory = null
                }
                val tts = synthesizer ?: createSynthesizer(directory).also {
                    synthesizer = it
                    synthesizerDirectory = directory.absolutePath
                }
                val speakerCount = tts.numSpeakers()
                if (speakerCount > 0 && voiceId >= speakerCount) {
                    throw IllegalArgumentException("Голос $voiceId недоступен (доступно $speakerCount)")
                }

                val output = makeAudioTrack(tts.sampleRate())
                track = output
                audioTrack = output
                waitIfPaused(request)
                if (speechStopped(request)) return@execute
                val chunks = splitForSpeech(text, language)
                var framesWritten = 0L
                var started = false
                val generation = GenerationConfig(
                    sid = voiceId,
                    speed = speed,
                    numSteps = 8,
                    extra = mapOf("lang" to language),
                )
                for ((index, chunk) in chunks.withIndex()) {
                    if (speechStopped(request)) break
                    val audio = tts.generateWithConfig(chunk.text, generation)
                    if (speechStopped(request)) break
                    waitIfPaused(request)
                    if (speechStopped(request)) break
                    if (!started) {
                        output.play()
                        started = true
                    }
                    publish(
                        SpeechStatus(
                            SpeechPhase.SPEAKING,
                            completed = index.toLong(),
                            total = chunks.size.toLong(),
                            currentText = chunk.text,
                            textOffset = chunk.start,
                            textLength = chunk.end - chunk.start,
                        )
                    )
                    framesWritten += writeSamples(output, audio.samples, request)
                }
                if (!speechStopped(request)) {
                    waitUntilPlayed(output, framesWritten, request)
                }
                if (!speechStopped(request)) publish(SpeechStatus(SpeechPhase.READY))
            } catch (error: Exception) {
                if (!speechStopped(request)) {
                    publish(SpeechStatus(SpeechPhase.ERROR, "Ошибка озвучки: ${error.message}"))
                }
            } finally {
                audioTrack = null
                try { track?.pause() } catch (_: IllegalStateException) { }
                try { track?.flush() } catch (_: IllegalStateException) { }
                track?.release()
            }
        }
    }

    /** Stop current speech; the model stays loaded for the next play request. */
    fun stop() {
        speechEpoch.incrementAndGet()
        pauseRequested.set(false)
        synchronized(pauseLock) { pauseLock.notifyAll() }
        try { audioTrack?.pause() } catch (_: IllegalStateException) { }
        try { audioTrack?.flush() } catch (_: IllegalStateException) { }
        try { audioTrack?.stop() } catch (_: IllegalStateException) { }
        if (!closed.get() && status.phase in setOf(SpeechPhase.LOADING, SpeechPhase.SPEAKING, SpeechPhase.PAUSED)) {
            publish(SpeechStatus(SpeechPhase.READY))
        }
    }

    fun pause() {
        if (closed.get() || status.phase !in setOf(SpeechPhase.LOADING, SpeechPhase.SPEAKING)) return
        pauseRequested.set(true)
        try { audioTrack?.pause() } catch (_: IllegalStateException) { }
        publish(status.copy(phase = SpeechPhase.PAUSED))
    }

    fun resume() {
        if (closed.get() || status.phase != SpeechPhase.PAUSED) return
        pauseRequested.set(false)
        try { audioTrack?.play() } catch (_: IllegalStateException) { }
        synchronized(pauseLock) { pauseLock.notifyAll() }
        publish(status.copy(phase = SpeechPhase.SPEAKING))
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stop()
        cancelInstall()
        executor.execute {
            synthesizer?.release()
            synthesizer = null
            synthesizerDirectory = null
        }
        executor.shutdown()
    }

    private fun createSynthesizer(dir: File): OfflineTts {
        val suffix = if (dir.name == "supertonic-3-full") ".onnx" else ".int8.onnx"
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, "duration_predictor$suffix").absolutePath,
                    textEncoder = File(dir, "text_encoder$suffix").absolutePath,
                    vectorEstimator = File(dir, "vector_estimator$suffix").absolutePath,
                    vocoder = File(dir, "vocoder$suffix").absolutePath,
                    ttsJson = File(dir, "tts.json").absolutePath,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").absolutePath,
                    voiceStyle = File(dir, "voice.bin").absolutePath,
                ),
                numThreads = 2,
                provider = "cpu",
            ),
            maxNumSentences = 1,
        )
        return OfflineTts(config = config)
    }

    private fun makeAudioTrack(sampleRate: Int): AudioTrack {
        require(sampleRate > 0) { "Модель вернула недопустимую частоту звука" }
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minimum > 0) { "Устройство не поддерживает звук модели" }
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(max(minimum, sampleRate))
            .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw IllegalStateException("Не удалось открыть аудиовыход")
        }
        return track
    }

    private fun writeSamples(track: AudioTrack, samples: FloatArray, request: Long): Long {
        val buffer = ShortArray(4096)
        var cursor = 0
        while (cursor < samples.size) {
            waitIfPaused(request)
            if (speechStopped(request)) break
            val count = minOf(buffer.size, samples.size - cursor)
            for (i in 0 until count) {
                buffer[i] = (samples[cursor + i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            var offset = 0
            while (offset < count) {
                waitIfPaused(request)
                if (speechStopped(request)) break
                val wrote = track.write(buffer, offset, count - offset, AudioTrack.WRITE_BLOCKING)
                if (wrote <= 0) throw IllegalStateException("Ошибка записи звука: $wrote")
                offset += wrote
            }
            cursor += offset
            if (offset < count) break
        }
        return cursor.toLong()
    }

    private fun waitUntilPlayed(track: AudioTrack, framesWritten: Long, request: Long) {
        var noProgressSince = System.currentTimeMillis()
        var lastPosition = -1L
        while (!speechStopped(request) &&
            track.playbackHeadPosition.toLong() < framesWritten
        ) {
            waitIfPaused(request)
            if (speechStopped(request)) break
            val position = track.playbackHeadPosition.toLong()
            if (position != lastPosition) {
                lastPosition = position
                noProgressSince = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - noProgressSince > 60_000L) {
                throw IllegalStateException("Воспроизведение звука остановилось")
            }
            Thread.sleep(30)
        }
    }

    private fun waitIfPaused(request: Long) {
        synchronized(pauseLock) {
            while (pauseRequested.get() && !speechStopped(request)) {
                pauseLock.wait(100L)
            }
        }
    }

    private fun publish(next: SpeechStatus) {
        status = next
        val listener = onStatus
        mainHandler.post { listener?.invoke(next) }
    }

    private fun speechStopped(request: Long): Boolean = closed.get() || speechEpoch.get() != request

    companion object {
        // Languages declared by the publisher for Supertonic 3.
        private val SUPPORTED_LANGUAGES = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et",
            "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl",
            "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
        )

    }
}
