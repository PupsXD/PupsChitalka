package com.ozvuchka.app.speech

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class VoiceUnavailableException(message: String) : IllegalStateException(message)

/**
 * Owns every synthesizer used during narration: in-process sherpa-onnx models and connections to
 * Android TTS engines such as RuVoice. One instance lives per process, so a model loaded when a
 * book is opened is ready when «Слушать» is pressed; models are released after a few idle
 * minutes. [synthesize] blocks and must be called from a background thread.
 */
internal class SynthesisHub private constructor(context: Context) {
    private val app = context.applicationContext
    private val models = HashMap<SpeechModel, OfflineTts>()
    private val systemClients = ConcurrentHashMap<String, SystemTtsClient>()
    private val modelLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val warmUpWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-voice-warmup").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    @Volatile private var inUse = false
    private val idleRelease = Runnable { if (!inUse) releaseAll() }

    /** Cores 0–3 on the Galaxy S24 Ultra are the Cortex-X4 and three A720: the fast cluster. */
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { if (it >= 8) 4 else (it / 2).coerceAtLeast(2) }

    fun isAvailable(voice: VoiceChoice, settings: SpeechSettings): Boolean = when (voice.engine) {
        VoiceEngine.SUPERTONIC -> supertonicModel(settings) != null
        VoiceEngine.KOKORO -> kokoroModel(settings) != null
        VoiceEngine.SYSTEM -> SystemVoices.isEngineInstalled(app, voice.enginePackage)
    }

    /** Renders [text]; [prosody] is the line's tone: its pace and pitch come from here, its loudness from the player. */
    fun synthesize(
        voice: VoiceChoice,
        text: String,
        language: String,
        settings: SpeechSettings,
        prosody: Prosody = Prosody.NEUTRAL,
    ): SynthesizedAudio {
        val speed = settings.speed * prosody.rate
        // Supertonic and Kokoro cannot change pitch: they speak slower by as much, and the player plays
        // the sound that much faster, which brings the pace back and raises the voice.
        val modelSpeed = speed / prosody.pitch
        return when (voice.engine) {
            VoiceEngine.SUPERTONIC -> {
                val model = supertonicModel(settings)
                    ?: throw VoiceUnavailableException("Голос Supertonic не загружен")
                val spoken = SpeechNormalizer.normalize(text, language, forSupertonic = true)
                if (spoken.isBlank()) return SynthesizedAudio(FloatArray(0), 44_100)
                val config = GenerationConfig(
                    sid = voice.speaker.coerceIn(0, 9),
                    // Supertone's own default pace is 1.05; the reader's 1.0 means that pace.
                    speed = modelSpeed * 1.05f,
                    numSteps = settings.supertonicSteps,
                    extra = mapOf("lang" to if (language == "en") "en" else "ru", "silence_duration" to "0.15"),
                )
                generate(model, spoken, config, speedUp = prosody.pitch)
            }
            VoiceEngine.KOKORO -> {
                val model = kokoroModel(settings)
                    ?: throw VoiceUnavailableException("Голос Kokoro не загружен")
                val spoken = SpeechNormalizer.normalize(text, "en")
                if (spoken.isBlank()) return SynthesizedAudio(FloatArray(0), 24_000)
                val config = GenerationConfig(
                    sid = voice.speaker,
                    speed = modelSpeed,
                    extra = mapOf("lang" to VoiceCatalog.kokoroLanguage(voice.speaker)),
                )
                generate(model, spoken, config, speedUp = prosody.pitch)
            }
            VoiceEngine.SYSTEM -> {
                val client = systemClients.getOrPut(voice.enginePackage) { SystemTtsClient(app, voice.enginePackage) }
                client.synthesize(text, voice.voiceName, language, speed, prosody.pitch)
            }
        }
    }

    /** Loads the voice in the background, so the first sentence starts without a model load. */
    fun warmUpAsync(voice: VoiceChoice, settings: SpeechSettings) {
        warmUpWorker.execute {
            runCatching {
                when (voice.engine) {
                    VoiceEngine.SUPERTONIC -> supertonicModel(settings)?.let { load(it) }
                    VoiceEngine.KOKORO -> kokoroModel(settings)?.let { load(it) }
                    VoiceEngine.SYSTEM -> if (SystemVoices.isEngineInstalled(app, voice.enginePackage)) {
                        systemClients.getOrPut(voice.enginePackage) { SystemTtsClient(app, voice.enginePackage) }
                    }
                }
            }
        }
        if (!inUse) scheduleRelease()
    }

    /** Narration service is running: keep models loaded. */
    fun hold() {
        inUse = true
        mainHandler.removeCallbacks(idleRelease)
    }

    /** Narration ended; keep the voice warm for a few minutes in case playback resumes. */
    fun unhold() {
        inUse = false
        scheduleRelease()
    }

    /** Frees native memory now unless narration is running; used when the system is low on memory. */
    fun releaseIfIdle() {
        if (!inUse) releaseAll()
    }

    private fun scheduleRelease() {
        mainHandler.removeCallbacks(idleRelease)
        mainHandler.postDelayed(idleRelease, IDLE_RELEASE_MS)
    }

    private fun supertonicModel(settings: SpeechSettings): SpeechModel? = when {
        settings.preferFullModels && SpeechModels.isInstalled(app, SpeechModel.SUPERTONIC_FULL) -> SpeechModel.SUPERTONIC_FULL
        SpeechModels.isInstalled(app, SpeechModel.SUPERTONIC) -> SpeechModel.SUPERTONIC
        SpeechModels.isInstalled(app, SpeechModel.SUPERTONIC_FULL) -> SpeechModel.SUPERTONIC_FULL
        else -> null
    }

    private fun kokoroModel(settings: SpeechSettings): SpeechModel? = when {
        settings.preferFullModels && SpeechModels.isInstalled(app, SpeechModel.KOKORO_FULL) -> SpeechModel.KOKORO_FULL
        SpeechModels.isInstalled(app, SpeechModel.KOKORO) -> SpeechModel.KOKORO
        SpeechModels.isInstalled(app, SpeechModel.KOKORO_FULL) -> SpeechModel.KOKORO_FULL
        else -> null
    }

    private fun generate(model: SpeechModel, text: String, config: GenerationConfig, speedUp: Float): SynthesizedAudio {
        val tts = load(model)
        // One native call at a time per model; a cancelled session may still finish its sentence.
        val audio = synchronized(tts) { tts.generateWithConfig(text, config) }
        return SynthesizedAudio(audio.samples, audio.sampleRate.takeIf { it > 0 } ?: tts.sampleRate(), speedUp = speedUp)
    }

    private fun load(model: SpeechModel): OfflineTts = synchronized(modelLock) {
        models[model]?.let { return it }
        // Keep at most one variant of each family in memory.
        val sibling = when (model) {
            SpeechModel.SUPERTONIC -> SpeechModel.SUPERTONIC_FULL
            SpeechModel.SUPERTONIC_FULL -> SpeechModel.SUPERTONIC
            SpeechModel.KOKORO -> SpeechModel.KOKORO_FULL
            SpeechModel.KOKORO_FULL -> SpeechModel.KOKORO
        }
        models.remove(sibling)?.let { old -> synchronized(old) { old.release() } }
        val directory = SpeechModels.directory(app, model)
        val config = when (model) {
            SpeechModel.SUPERTONIC, SpeechModel.SUPERTONIC_FULL -> {
                val suffix = if (model == SpeechModel.SUPERTONIC_FULL) ".onnx" else ".int8.onnx"
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        supertonic = OfflineTtsSupertonicModelConfig(
                            durationPredictor = File(directory, "duration_predictor$suffix").absolutePath,
                            textEncoder = File(directory, "text_encoder$suffix").absolutePath,
                            vectorEstimator = File(directory, "vector_estimator$suffix").absolutePath,
                            vocoder = File(directory, "vocoder$suffix").absolutePath,
                            ttsJson = File(directory, "tts.json").absolutePath,
                            unicodeIndexer = File(directory, "unicode_indexer.bin").absolutePath,
                            voiceStyle = File(directory, "voice.bin").absolutePath,
                        ),
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
            }
            SpeechModel.KOKORO, SpeechModel.KOKORO_FULL -> {
                val modelFile = if (model == SpeechModel.KOKORO_FULL) "model.onnx" else "model.int8.onnx"
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(directory, modelFile).absolutePath,
                            voices = File(directory, "voices.bin").absolutePath,
                            tokens = File(directory, "tokens.txt").absolutePath,
                            dataDir = File(directory, "espeak-ng-data").absolutePath,
                            // A multilingual Kokoro model needs either a lexicon or a language;
                            // without both the native layer terminates the process.
                            lang = "en-us",
                        ),
                        numThreads = threads,
                        provider = "cpu",
                    ),
                    maxNumSentences = 1,
                )
            }
        }
        OfflineTts(config = config).also { models[model] = it }
    }

    /** An engine was replaced by a newer build: its process restarted, so the connection kept to it is dead. */
    fun forgetSystemEngine(enginePackage: String) {
        systemClients.remove(enginePackage)?.shutdown()
    }

    private fun releaseAll() {
        synchronized(modelLock) {
            models.values.forEach { tts -> synchronized(tts) { tts.release() } }
            models.clear()
        }
        systemClients.values.forEach { it.shutdown() }
        systemClients.clear()
    }

    companion object {
        private const val IDLE_RELEASE_MS = 5 * 60_000L
        @Volatile private var instance: SynthesisHub? = null

        fun shared(context: Context): SynthesisHub =
            instance ?: synchronized(this) { instance ?: SynthesisHub(context).also { instance = it } }
    }
}

/**
 * Uses an installed Android TTS engine (RuVoice/Silero, Google, Samsung…) as a synthesizer:
 * each sentence is rendered to a WAV file, so it joins the same gapless playback pipeline,
 * highlighting and pause handling as the built-in voices.
 */
internal class SystemTtsClient(context: Context, val enginePackage: String) {
    private val app = context.applicationContext
    private val ready = CountDownLatch(1)
    @Volatile private var initStatus = TextToSpeech.ERROR
    private val waiting = ConcurrentHashMap<String, CountDownLatch>()
    private val failures = ConcurrentHashMap<String, Int>()
    /** Word starts the engine reported for each utterance (RuVoice does; many engines do not). */
    private val words = ConcurrentHashMap<String, MutableList<WordMark>>()
    private val tts: TextToSpeech
    private var appliedVoice: String? = null
    private var appliedLanguage: String? = null
    private var appliedRate = -1f
    private var appliedPitch = -1f
    private val directory = File(File(app.cacheDir, "system-tts"), enginePackage.replace(Regex("[^A-Za-z0-9._-]"), "_")).apply {
        mkdirs()
        // Files left behind if the process died in the middle of a sentence.
        listFiles()?.forEach { it.delete() }
    }

    init {
        tts = TextToSpeech(app, { status ->
            initStatus = status
            ready.countDown()
        }, enginePackage)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            // File synthesis reports each word right away, with the frame where it starts in the file.
            override fun onRangeStart(utteranceId: String?, start: Int, end: Int, frame: Int) {
                utteranceId?.let { words[it]?.add(WordMark(frame, start, end)) }
            }
            override fun onDone(utteranceId: String?) {
                utteranceId?.let { waiting[it]?.countDown() }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                utteranceId?.let {
                    failures[it] = TextToSpeech.ERROR
                    waiting[it]?.countDown()
                }
            }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let {
                    failures[it] = errorCode
                    waiting[it]?.countDown()
                }
            }
        })
    }

    /**
     * [pitch] scales the pitch set in Android's speech settings, which the engine uses when nobody asks
     * for another. RuVoice passes it to the model, so the voice is not distorted.
     */
    @Synchronized
    fun synthesize(text: String, voiceName: String, language: String, speed: Float, pitch: Float = 1f): SynthesizedAudio {
        if (!ready.await(15, TimeUnit.SECONDS) || initStatus != TextToSpeech.SUCCESS) {
            throw VoiceUnavailableException("Движок синтеза $enginePackage не отвечает")
        }
        applyVoice(voiceName, language)
        if (appliedRate != speed) {
            tts.setSpeechRate(speed)
            appliedRate = speed
        }
        val systemPitch = Settings.Secure.getInt(app.contentResolver, Settings.Secure.TTS_DEFAULT_PITCH, 100) / 100f
        val wantedPitch = systemPitch * pitch
        if (appliedPitch != wantedPitch) {
            tts.setPitch(wantedPitch)
            appliedPitch = wantedPitch
        }
        val id = UUID.randomUUID().toString()
        val file = File(directory, "$id.wav")
        val latch = CountDownLatch(1)
        waiting[id] = latch
        words[id] = java.util.Collections.synchronizedList(ArrayList())
        try {
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, id) }
            val result = tts.synthesizeToFile(text, params, file, id)
            if (result != TextToSpeech.SUCCESS) throw IllegalStateException("движок отклонил запрос")
            val timeoutMs = 20_000L + text.length * 150L
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                tts.stop()
                throw IllegalStateException("движок не успел озвучить фразу")
            }
            failures.remove(id)?.let { code -> throw IllegalStateException("ошибка движка $code") }
            val audio = WavReader.read(file)
            val marks = words[id]?.let { list -> synchronized(list) { list.sortedBy { it.frame } } }.orEmpty()
            return SynthesizedAudio(audio.samples, audio.sampleRate, marks)
        } finally {
            waiting.remove(id)
            words.remove(id)
            file.delete()
        }
    }

    private fun applyVoice(voiceName: String, language: String) {
        if (voiceName.isNotEmpty() && voiceName == appliedVoice) return
        if (voiceName.isEmpty() && appliedVoice == null && appliedLanguage == language) return
        val voice = if (voiceName.isNotEmpty()) runCatching { tts.voices }.getOrNull()?.firstOrNull { it.name == voiceName } else null
        if (voice != null) {
            tts.voice = voice
            appliedVoice = voiceName
        } else {
            tts.language = Locale.forLanguageTag(if (language == "en") "en-US" else "ru-RU")
            appliedVoice = null
        }
        appliedLanguage = language
    }

    fun shutdown() {
        waiting.values.forEach { it.countDown() }
        runCatching { tts.stop() }
        runCatching { tts.shutdown() }
    }
}

/** Voice of an installed system engine, as shown in the picker. */
data class SystemVoiceInfo(
    val enginePackage: String,
    val engineLabel: String,
    val name: String,
    val language: String,
    val needsNetwork: Boolean,
    val notInstalled: Boolean,
    val quality: Int,
    /** The engine reads its language with this voice when none is chosen: what «По умолчанию» sounds like. */
    val isDefault: Boolean = false,
)

data class SystemEngineInfo(val packageName: String, val label: String)

/** Discovery of installed TTS engines and their Russian and English voices. */
object SystemVoices {
    fun isEngineInstalled(context: Context, packageName: String): Boolean =
        engines(context).any { it.packageName == packageName }

    fun engines(context: Context): List<SystemEngineInfo> {
        val intent = android.content.Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
        val pm = context.packageManager
        return pm.queryIntentServices(intent, 0).mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            SystemEngineInfo(service.packageName, info.loadLabel(pm).toString())
        }.distinctBy { it.packageName }
            .filter { it.packageName != context.packageName }
    }

    /**
     * Connects to one engine and lists its Russian and English voices. Must be called off the
     * main thread; the engine is bound only for the duration of the call.
     */
    fun voices(context: Context, engine: SystemEngineInfo): List<SystemVoiceInfo> {
        val app = context.applicationContext
        val latch = CountDownLatch(1)
        var status = TextToSpeech.ERROR
        val tts = TextToSpeech(app, { code ->
            status = code
            latch.countDown()
        }, engine.packageName)
        return try {
            if (!latch.await(10, TimeUnit.SECONDS) || status != TextToSpeech.SUCCESS) return emptyList()
            val voices: Set<Voice> = runCatching { tts.voices }.getOrNull().orEmpty()
            // What «По умолчанию» sounds like: the voice the engine picks when narration sets only the language.
            val defaults = listOf("ru-RU", "en-US").mapNotNull { tag ->
                runCatching {
                    if (tts.setLanguage(Locale.forLanguageTag(tag)) >= TextToSpeech.LANG_AVAILABLE) tts.voice?.name else null
                }.getOrNull()
            }.toSet()
            voices.filter { it.locale.language == "ru" || it.locale.language == "en" }
                .map { voice ->
                    SystemVoiceInfo(
                        enginePackage = engine.packageName,
                        engineLabel = engine.label,
                        name = voice.name,
                        language = voice.locale.language,
                        needsNetwork = voice.isNetworkConnectionRequired,
                        notInstalled = voice.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
                        quality = voice.quality,
                        isDefault = voice.name in defaults,
                    )
                }
                .sortedWith(
                    compareBy<SystemVoiceInfo> { it.language != "ru" }
                        .thenBy { it.needsNetwork }
                        .thenBy { it.notInstalled }
                        .thenByDescending { it.quality }
                        .thenBy { it.name },
                )
        } finally {
            runCatching { tts.shutdown() }
        }
    }

    /** Short engine names for the picker: «RuVoice», «Google», «Samsung», otherwise the app label. */
    fun engineTitle(packageName: String, label: String? = null): String = when (packageName) {
        VoiceCatalog.RUVOICE_PACKAGE -> "RuVoice"
        "com.google.android.tts" -> "Google"
        "com.samsung.SMT" -> "Samsung"
        else -> label?.takeIf { it.isNotBlank() } ?: packageName
    }

    /** Gender of well-known voices, for choosing character voices; null when the name does not say. */
    fun gender(voice: SystemVoiceInfo): SpeechRole? {
        val name = voice.name.lowercase()
        return when {
            voice.enginePackage == VoiceCatalog.RUVOICE_PACKAGE -> when (name.substringBefore('-')) {
                "aidar", "eugene" -> SpeechRole.MALE
                "baya", "kseniya", "xenia" -> SpeechRole.FEMALE
                else -> null
            }
            Regex("smtm\\d+$").containsMatchIn(name) -> SpeechRole.MALE
            Regex("smtf\\d+$").containsMatchIn(name) -> SpeechRole.FEMALE
            else -> null
        }
    }

    /** Human-friendly names for common engine voice identifiers. */
    fun describe(voice: SystemVoiceInfo): String {
        val name = voice.name
        val google = Regex("^([a-z]{2,3})-([a-z]{2})-x-([a-z0-9]+)-(local|network)$").find(name)
        val samsung = Regex("SMT([fm])(\\d+)$").find(name)
        val base = when {
            voice.enginePackage == VoiceCatalog.RUVOICE_PACKAGE -> {
                val parts = name.split('-')
                val speaker = parts.first().replaceFirstChar { it.uppercase() }
                val pack = parts.drop(1).filter { it != "ru" }.joinToString(" ")
                if (pack.isEmpty()) speaker else "$speaker · $pack"
            }
            google != null -> "${google.groupValues[1]}-${google.groupValues[2].uppercase()} · голос ${google.groupValues[3]}"
            samsung != null -> (if (samsung.groupValues[1] == "f") "Женский " else "Мужской ") + (samsung.groupValues[2].toInt() + 1)
            else -> name
        }
        val tags = buildList {
            if (voice.needsNetwork) add("нужен интернет")
            if (voice.notInstalled) add("не скачан")
        }
        return if (tags.isEmpty()) base else "$base · ${tags.joinToString()}"
    }
}
