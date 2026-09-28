package com.ozvuchka.app.speech

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/** Sentences of a book in reading order, addressed by a stable index. */
internal interface SegmentSource {
    fun get(index: Int): SpeechSegment?

    /** The paragraph (or title) text a segment was cut from, for exact splitting. */
    fun sourceText(segment: SpeechSegment): String

    /** True while more text may still arrive (the next web chapter is loading). */
    fun mayGrow(): Boolean = false
}

/** Segments of a book from one chapter onward; later chapters are split only when reached. */
internal class BookSegmentSource(
    chapters: List<Chapter>,
    private val firstChapter: Int,
    /** Cut characters' lines into their own segments, for dialogue voices. */
    val splitDialogue: Boolean = false,
    /** The book's characters, so a name next to a line tells who says it. */
    val cast: Cast = Cast.EMPTY,
) : SegmentSource {
    private val chapters = ArrayList(chapters)
    private val segments = ArrayList<SpeechSegment>()
    private var nextChapter = firstChapter

    /** True while a next web chapter may still come; narration waits at the end instead of stopping. */
    @Volatile var growing = false

    /** Called once when narration runs out of text while [growing]: time to fetch the next chapter. */
    @Volatile var onExhausted: (() -> Unit)? = null

    override fun mayGrow(): Boolean {
        if (growing) onExhausted?.let { callback ->
            onExhausted = null
            callback()
        }
        return growing
    }

    /** A chapter fetched while narration runs; it is split when narration reaches it. */
    @Synchronized
    fun append(chapter: Chapter) {
        chapters += chapter
    }

    @Synchronized
    override fun get(index: Int): SpeechSegment? {
        if (index < 0) return null
        while (index >= segments.size && nextChapter < chapters.size) {
            val chapter = chapters[nextChapter]
            val headings = chapter.styles.filterValues { it.kind == ParagraphKind.HEADING || it.kind == ParagraphKind.SUBHEADING }.keys
            segments += chapterSegments(nextChapter, chapter.title, chapter.paragraphs, splitDialogue = splitDialogue, headings = headings, cast = cast)
            nextChapter++
        }
        return segments.getOrNull(index)
    }

    @Synchronized
    override fun sourceText(segment: SpeechSegment): String {
        val chapter = chapters.getOrNull(segment.chapter) ?: return segment.text
        return if (segment.paragraph < 0) chapter.title.trim() else chapter.paragraphs.getOrNull(segment.paragraph) ?: segment.text
    }

    /** The segment to start from for a reading position; the chapter title is read at a chapter start. */
    @Synchronized
    fun indexOf(chapter: Int, paragraph: Int, offset: Int): Int {
        val atChapterStart = paragraph <= 0 && offset <= 0
        var index = 0
        while (true) {
            val segment = get(index) ?: return max(0, segments.size - 1)
            if (segment.chapter > chapter) return index
            if (segment.chapter == chapter) {
                if (atChapterStart) return index
                if (segment.paragraph > paragraph) return index
                if (segment.paragraph == paragraph && segment.end > offset) return index
            }
            index++
        }
    }
}

/**
 * Places an engine's word starts in a rendered sentence: frames shift by the trimmed lead-in and
 * scale to the output rate; ranges in the spoken text become offsets in the paragraph [source].
 */
internal fun placeWords(
    words: List<WordMark>,
    trimmedFrames: Int,
    sourceRate: Int,
    outputRate: Int,
    outputLength: Int,
    source: String,
    segment: SpeechSegment,
): List<WordMark> = words.mapNotNull { word ->
    if (word.start < 0 || word.end <= word.start || word.end > segment.text.length) return@mapNotNull null
    val frame = ((word.frame - trimmedFrames).coerceAtLeast(0).toLong() * outputRate / sourceRate)
        .coerceAtMost(outputLength.toLong()).toInt()
    val start = originalOffset(source, segment.start, segment.end, word.start)
    val end = originalOffset(source, segment.start, segment.end, word.end - 1) + 1
    if (end > start) WordMark(frame, start, end) else null
}.sortedBy { it.frame }

/** A single sentence for voice previews. */
internal class SingleSegmentSource(private val segment: SpeechSegment) : SegmentSource {
    override fun get(index: Int): SpeechSegment? = segment.takeIf { index == 0 }
    override fun sourceText(segment: SpeechSegment): String = segment.text
}

/**
 * Gapless narration: a synthesis thread renders sentences ahead of playback (up to a minute of
 * audio), a writer thread streams them into one AudioTrack, and a monitor reports which sentence
 * is audible right now from the track's presentation timestamp. Pause and resume are instant
 * because nothing is re-synthesized.
 */
internal class NarrationPlayer(
    private val hub: SynthesisHub,
    private val listener: Listener,
) {
    /**
     * Callbacks arrive on player threads. Each carries the id of the session that produced it,
     * so a late event from a cancelled session (after a skip) can be told apart and ignored.
     */
    interface Listener {
        fun onSegmentStarted(session: Long, index: Int, segment: SpeechSegment)
        /** The word now audible in segment [index], as a range of its paragraph (or title). */
        fun onWordStarted(session: Long, index: Int, offset: Int, length: Int)
        fun onBufferingChanged(session: Long, buffering: Boolean)
        /** Playback stopped before [segment] because a chapter-end sleep timer was set. */
        fun onChapterBoundaryPause(session: Long, index: Int, segment: SpeechSegment)
        fun onCompleted(session: Long)
        fun onError(session: Long, message: String)
        fun onVoiceFallback(session: Long, message: String)
    }

    private val loudness = LoudnessMatcher()
    private val sessionIds = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var session: Session? = null

    /** Id of the running session, or -1. */
    val sessionId: Long get() = session?.id ?: -1L

    val isActive: Boolean get() = session != null
    val isPaused: Boolean get() = session?.paused == true
    val currentIndex: Int? get() = session?.currentIndex

    /** Seconds the current sentence has been playing; used by «previous sentence». */
    val secondsIntoCurrent: Float get() = session?.secondsIntoCurrent() ?: 0f

    @Synchronized
    fun play(
        source: SegmentSource,
        startIndex: Int,
        settings: SpeechSettings,
        stopAtChapterEnd: Boolean = false,
        pronunciations: PronunciationDictionary = PronunciationDictionary.EMPTY,
    ) {
        session?.cancel()
        session = Session(
            source = source,
            startIndex = startIndex,
            settings = settings,
            stopAtChapterEnd = stopAtChapterEnd,
            pronunciations = pronunciations,
        ).also { it.start() }
    }

    @Synchronized
    fun pause() {
        session?.pause()
    }

    @Synchronized
    fun resume() {
        session?.resume()
    }

    @Synchronized
    fun stop() {
        session?.cancel()
        session = null
    }

    fun setStopAtChapterEnd(enabled: Boolean) {
        session?.stopAtChapterEnd = enabled
    }

    @Volatile private var fadeGeneration = 0

    /** Lowers the volume over [durationMs], then pauses: used by the sleep timer. */
    fun fadeOutAndPause(durationMs: Long, onPaused: (session: Long) -> Unit) {
        val current = session ?: return
        val generation = ++fadeGeneration
        thread(name = "narration-fade", isDaemon = true) {
            val steps = 30
            for (step in steps downTo 0) {
                if (current.cancelled || current.paused || generation != fadeGeneration) break
                current.setVolume(step.toFloat() / steps)
                Thread.sleep(durationMs / steps)
            }
            if (generation != fadeGeneration) return@thread
            if (!current.cancelled) {
                current.pause()
                current.setVolume(1f)
                onPaused(current.id)
            }
        }
    }

    /** Stops a sleep fade and brings the volume back: the listener shook the phone for more time. */
    fun cancelFade() {
        fadeGeneration++
        session?.setVolume(1f)
    }

    private class Rendered(
        val index: Int,
        val segment: SpeechSegment,
        val samples: FloatArray,
        /** Word starts in frames of [samples], with paragraph offsets; empty if the engine gave none. */
        val words: List<WordMark> = emptyList(),
    ) {
        @Volatile var startFrame = 0L
        @Volatile var endFrame = 0L
    }

    private inner class Session(
        val id: Long = sessionIds.incrementAndGet(),
        private val source: SegmentSource,
        private val startIndex: Int,
        private val settings: SpeechSettings,
        @Volatile var stopAtChapterEnd: Boolean,
        private val pronunciations: PronunciationDictionary,
    ) {
        @Volatile var cancelled = false
        @Volatile var paused = false
        @Volatile var currentIndex: Int = startIndex

        private val lock = Object()
        private val queue = ArrayDeque<Rendered>()
        private var queuedFrames = 0L
        private var synthesisFinished = false
        @Volatile private var outputRate = 0
        @Volatile private var track: AudioTrack? = null
        @Volatile private var framesWritten = 0L
        @Volatile private var tailPadding = 0L
        private val written = ArrayList<Rendered>()
        @Volatile private var current: Rendered? = null
        private val timestamp = AudioTimestamp()
        /** Voices that failed in this session; later sentences go straight to the fallback. */
        private val brokenVoices = HashSet<VoiceChoice>()

        fun start() {
            thread(name = "narration-synth", isDaemon = true) { synthesisLoop() }
            thread(name = "narration-writer", isDaemon = true) { writerLoop() }
            thread(name = "narration-monitor", isDaemon = true) { monitorLoop() }
        }

        fun pause() {
            paused = true
            runCatching { track?.pause() }
        }

        fun resume() {
            paused = false
            runCatching { track?.play() }
            synchronized(lock) { lock.notifyAll() }
        }

        fun setVolume(volume: Float) {
            runCatching { track?.setVolume(volume) }
        }

        fun cancel() {
            cancelled = true
            synchronized(lock) { lock.notifyAll() }
            // Silence at once and unblock a writer waiting inside write(); the writer releases the track.
            track?.let { output ->
                runCatching { output.pause() }
                runCatching { output.flush() }
                runCatching { output.stop() }
            }
        }

        fun secondsIntoCurrent(): Float {
            val item = current ?: return 0f
            val output = track ?: return 0f
            val rate = outputRate.takeIf { it > 0 } ?: return 0f
            return ((presentedFrame(output) - item.startFrame).coerceAtLeast(0) / rate.toFloat())
        }

        // ---------------------------------------------------------------- synthesis

        private fun synthesisLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
            try {
                val pending = ArrayDeque<Pair<Int, SpeechSegment>>()
                var nextIndex = startIndex
                source.get(startIndex)?.let { first ->
                    // The first clause of a long opening sentence is rendered alone so sound starts sooner.
                    splitForQuickStart(first, source.sourceText(first)).forEach { pending.addLast(startIndex to it) }
                    nextIndex = startIndex + 1
                }
                fun nextFromSource(): Pair<Int, SpeechSegment>? {
                    while (!cancelled) {
                        source.get(nextIndex)?.let { return (nextIndex to it).also { nextIndex++ } }
                        // The next web chapter is on its way: wait for it rather than end the book.
                        if (!source.mayGrow()) return null
                        Thread.sleep(300)
                    }
                    return null
                }
                while (!cancelled) {
                    waitForRoom()
                    if (cancelled) break
                    val (index, segment) = pending.removeFirstOrNull() ?: nextFromSource() ?: break
                    val rendered = render(index, segment) ?: continue
                    synchronized(lock) {
                        queue.addLast(rendered)
                        queuedFrames += rendered.samples.size
                        lock.notifyAll()
                    }
                }
            } catch (error: Throwable) {
                if (!cancelled) fail(error.message ?: error.javaClass.simpleName)
                Log.w(TAG, "Synthesis stopped", error)
            } finally {
                synchronized(lock) {
                    synthesisFinished = true
                    lock.notifyAll()
                }
            }
        }

        private fun waitForRoom() {
            synchronized(lock) {
                while (!cancelled) {
                    val rate = outputRate
                    val full = queue.size >= MAX_QUEUED_SEGMENTS ||
                        (rate > 0 && queuedFrames >= rate.toLong() * MAX_AHEAD_SECONDS)
                    if (!full) return
                    lock.wait(250)
                }
            }
        }

        private fun render(index: Int, segment: SpeechSegment): Rendered? {
            var voice = settings.voiceFor(segment.language, segment.role)
            // Kokoro speaks only English; anything else goes to the Russian voice.
            if (voice.engine == VoiceEngine.KOKORO && segment.language != "en") {
                voice = settings.voiceFor("ru", segment.role).takeIf { it.engine != VoiceEngine.KOKORO } ?: settings.russianVoice
            }
            if (voice.engine == VoiceEngine.KOKORO && !hub.isAvailable(voice, settings)) voice = fallbackVoice(voice) ?: voice
            if (voice in brokenVoices) voice = fallbackVoice(voice) ?: voice
            // The reader's pronunciations go in the form each engine understands.
            var rewrite = pronunciations.apply(segment.text, stressStyleFor(voice))
            val audio = try {
                hub.synthesize(voice, rewrite.text, segment.language, settings)
            } catch (error: Exception) {
                if (cancelled) return null
                val fallback = fallbackVoice(voice) ?: throw error
                Log.w(TAG, "Voice ${voice.encode()} failed, using ${fallback.encode()}", error)
                brokenVoices += voice
                listener.onVoiceFallback(id, "${error.message ?: "Голос недоступен"}. Читает встроенный голос.")
                voice = fallback
                rewrite = pronunciations.apply(segment.text, stressStyleFor(fallback))
                hub.synthesize(fallback, rewrite.text, segment.language, settings)
            }
            if (cancelled) return null
            val rate = synchronized(lock) {
                if (outputRate == 0) outputRate = audio.sampleRate.coerceIn(8_000, 48_000)
                outputRate
            }
            val bounds = AudioShaping.speechBounds(audio.samples, audio.sampleRate)
            var samples = if (bounds.isEmpty()) FloatArray(0) else audio.samples.copyOfRange(bounds.first, bounds.last + 1)
            var words = emptyList<WordMark>()
            if (samples.isNotEmpty()) {
                val gain = loudness.gainFor(voice.encode(), AudioShaping.speechRms(samples, audio.sampleRate))
                samples = Resampler.resample(samples, audio.sampleRate, rate)
                AudioShaping.applyGain(samples, gain)
                AudioShaping.fadeEdges(samples, rate)
                if (audio.words.isNotEmpty()) {
                    val spokenWords = audio.words.map { word ->
                        val range = rewrite.originalRange(word.start, word.end)
                        WordMark(word.frame, range.first, range.last + 1)
                    }
                    words = placeWords(spokenWords, bounds.first, audio.sampleRate, rate, samples.size, source.sourceText(segment), segment)
                }
            }
            val pauseMs = segment.pause.baseMs * settings.pauseScale / settings.speed.coerceAtLeast(0.5f)
            val pauseFrames = (rate * pauseMs / 1000f).toInt()
            val output = FloatArray(samples.size + pauseFrames)
            System.arraycopy(samples, 0, output, 0, samples.size)
            return Rendered(index, segment, output, words)
        }

        private fun fallbackVoice(failed: VoiceChoice): VoiceChoice? {
            if (failed.engine == VoiceEngine.SUPERTONIC) return null
            val builtIn = VoiceChoice(VoiceEngine.SUPERTONIC, settings.russianVoice.speaker.takeIf {
                settings.russianVoice.engine == VoiceEngine.SUPERTONIC
            } ?: 0)
            return builtIn.takeIf { hub.isAvailable(it, settings) }
        }

        // ---------------------------------------------------------------- playback

        private fun writerLoop() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var lastChapter: Int? = null
            try {
                while (!cancelled) {
                    val item = synchronized(lock) {
                        while (!cancelled && queue.isEmpty() && !synthesisFinished) lock.wait(250)
                        queue.firstOrNull()
                    }
                    if (cancelled) break
                    if (item == null) {
                        finishPlayback()
                        break
                    }
                    if (stopAtChapterEnd && lastChapter != null && item.segment.chapter != lastChapter) {
                        waitUntilPlayed(framesWritten)
                        if (cancelled) break
                        stopAtChapterEnd = false
                        pause()
                        listener.onChapterBoundaryPause(id, item.index, item.segment)
                        synchronized(lock) { while (paused && !cancelled) lock.wait(250) }
                        if (cancelled) break
                    }
                    synchronized(lock) {
                        queue.removeFirst()
                        queuedFrames -= item.samples.size
                        lock.notifyAll()
                    }
                    val output = ensureTrack()
                    if (cancelled) break
                    item.startFrame = framesWritten
                    item.endFrame = framesWritten + item.samples.size
                    synchronized(written) { written.add(item) }
                    writeFully(output, item.samples)
                    lastChapter = item.segment.chapter
                }
            } catch (error: Throwable) {
                if (!cancelled) fail(error.message ?: "Ошибка вывода звука")
                Log.w(TAG, "Playback stopped", error)
            } finally {
                releaseTrack()
            }
        }

        private fun writeFully(output: AudioTrack, samples: FloatArray) {
            var offset = 0
            while (offset < samples.size && !cancelled) {
                val count = min(WRITE_CHUNK, samples.size - offset)
                val result = output.write(samples, offset, count, AudioTrack.WRITE_BLOCKING)
                if (result < 0) {
                    if (cancelled) return
                    throw IllegalStateException("Ошибка вывода звука ($result)")
                }
                if (result == 0) Thread.sleep(10)
                offset += result
                framesWritten += result
            }
        }

        private fun ensureTrack(): AudioTrack {
            track?.let { return it }
            val rate = outputRate
            val minimum = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            check(minimum > 0) { "Устройство не поддерживает звук $rate Гц" }
            val output = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                // Half a second of buffer; deep-buffer output lets the CPU sleep between writes.
                .setBufferSizeInBytes(max(minimum * 2, rate * 4 / 2))
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
                .build()
            if (output.state != AudioTrack.STATE_INITIALIZED) {
                output.release()
                throw IllegalStateException("Не удалось открыть аудиовыход")
            }
            tailPadding = output.bufferSizeInFrames.toLong().coerceAtLeast(rate / 4L)
            track = output
            if (!paused && !cancelled) output.play()
            return output
        }

        private fun finishPlayback() {
            val output = track
            if (output != null) {
                val lastFrame = framesWritten
                // Padding guarantees the final sentence crosses the track's start threshold and plays out.
                writeFully(output, FloatArray(tailPadding.toInt()))
                waitUntilPlayed(lastFrame)
            }
            if (!cancelled) listener.onCompleted(id)
        }

        private fun waitUntilPlayed(target: Long) {
            val output = track ?: return
            var lastPosition = -1L
            var stalledSince = System.currentTimeMillis()
            while (!cancelled) {
                val position = presentedFrame(output)
                if (position >= target) return
                if (paused) {
                    stalledSince = System.currentTimeMillis()
                } else if (position != lastPosition) {
                    lastPosition = position
                    stalledSince = System.currentTimeMillis()
                } else if (System.currentTimeMillis() - stalledSince > 4_000) {
                    Log.w(TAG, "Playback head stalled at $position of $target")
                    return
                }
                Thread.sleep(30)
            }
        }

        private fun releaseTrack() {
            track?.let { output ->
                runCatching { output.pause() }
                runCatching { output.flush() }
                runCatching { output.release() }
            }
            track = null
        }

        /** Frame being heard now: the presentation timestamp, extrapolated, never past the head. */
        private fun presentedFrame(output: AudioTrack): Long {
            val head = output.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (paused) return head
            return try {
                if (output.getTimestamp(timestamp) && outputRate > 0) {
                    val elapsed = System.nanoTime() - timestamp.nanoTime
                    (timestamp.framePosition + elapsed * outputRate / 1_000_000_000L).coerceIn(0L, head)
                } else {
                    head
                }
            } catch (_: Exception) {
                head
            }
        }

        // ---------------------------------------------------------------- position

        private fun monitorLoop() {
            var buffering = false
            var spokenWord = -1
            listener.onBufferingChanged(id, true)
            buffering = true
            while (!cancelled) {
                val output = track
                var starving = !paused
                if (output != null && outputRate > 0) {
                    val position = presentedFrame(output)
                    val playing = synchronized(written) {
                        while (written.size > 1 && written[0].endFrame <= position) written.removeAt(0)
                        written.firstOrNull { position < it.endFrame } ?: written.lastOrNull()
                    }
                    if (playing != null && playing !== current) {
                        current = playing
                        currentIndex = playing.index
                        spokenWord = -1
                        if (!cancelled) listener.onSegmentStarted(id, playing.index, playing.segment)
                    }
                    if (playing != null && playing.words.isNotEmpty()) {
                        val into = position - playing.startFrame
                        var word = spokenWord
                        while (word + 1 < playing.words.size && playing.words[word + 1].frame <= into) word++
                        if (word != spokenWord) {
                            spokenWord = word
                            val mark = playing.words[word]
                            if (!cancelled) listener.onWordStarted(id, playing.index, mark.start, mark.end - mark.start)
                        }
                    }
                    val finishing = synchronized(lock) { synthesisFinished && queue.isEmpty() }
                    starving = !paused && !finishing && position >= framesWritten - outputRate / 10
                }
                if (starving != buffering && !cancelled) {
                    buffering = starving
                    listener.onBufferingChanged(id, starving)
                }
                Thread.sleep(MONITOR_PERIOD_MS)
            }
        }

        private fun fail(message: String) {
            if (cancelled) return
            cancel()
            listener.onError(id, message)
        }
    }

    companion object {
        private const val TAG = "OzvuchkaPlayer"
        private const val MAX_QUEUED_SEGMENTS = 24
        private const val MAX_AHEAD_SECONDS = 60
        private const val WRITE_CHUNK = 2048
        private const val MONITOR_PERIOD_MS = 40L
    }
}
