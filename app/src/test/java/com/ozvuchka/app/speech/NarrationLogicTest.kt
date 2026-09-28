package com.ozvuchka.app.speech

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.chapterProgressOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class NarrationLogicTest {
    @Test
    fun languageFollowsTheDominantScript() {
        assertEquals("ru", detectSpeechLanguage("Он открыл Microsoft Word и начал писать.", "ru"))
        assertEquals("en", detectSpeechLanguage("I don't know what you mean, she said.", "ru"))
        assertEquals("ru", detectSpeechLanguage("— Привет! Как дела?", "en"))
        assertEquals("ru", detectSpeechLanguage("…", "ru"))
        assertEquals("en", dominantLanguage(listOf("It was a bright cold day in April.", "Глава 1")))
    }

    @Test
    fun chapterSegmentsSpeakTitleOnceAndMarkParagraphEnds() {
        val segments = chapterSegments(
            chapterIndex = 2,
            title = "Глава третья",
            paragraphs = listOf("Первое предложение. Второе предложение.", "", "Новый абзац."),
        )
        assertEquals(-1, segments.first().paragraph)
        assertEquals(SegmentPause.TITLE, segments.first().pause)
        val body = segments.drop(1)
        assertEquals(listOf(0, 0, 2), body.map { it.paragraph })
        assertEquals(SegmentPause.SENTENCE, body[0].pause)
        assertEquals(SegmentPause.PARAGRAPH, body[1].pause)
        assertEquals(SegmentPause.CHAPTER_END, body[2].pause)
        assertTrue(segments.all { it.chapter == 2 })

        val titledByText = chapterSegments(0, "Пролог", listOf("Пролог", "Текст."))
        assertEquals(0, titledByText.first().paragraph)
    }

    @Test
    fun longSentencesSplitAfterPunctuation() {
        val sentence = "Он шёл долго, " + "очень ".repeat(40) + "долго, и наконец пришёл домой."
        val chunks = splitForSpeech(sentence, "ru", maxChars = 120)
        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 120 })
        assertEquals(sentence, chunks.joinToString(" ") { it.text })
    }

    @Test
    fun quickStartSplitKeepsExactSourceCoordinates() {
        val paragraph = "Когда поезд наконец остановился у маленькой станции, пассажиры высыпали на платформу, " +
            "и в холодном воздухе смешались голоса, запах угля и далёкий лай собак, провожавших состав."
        val segment = chapterSegments(0, null, listOf(paragraph)).single()
        val parts = splitForQuickStart(segment, paragraph)
        assertEquals(2, parts.size)
        assertEquals(segment.start, parts[0].start)
        assertEquals(parts[0].end, parts[1].start)
        assertEquals(segment.end, parts[1].end)
        assertEquals(parts[1].text, paragraph.substring(parts[1].start, parts[1].end))
        assertEquals(SegmentPause.CLAUSE, parts[0].pause)
        assertEquals(segment.pause, parts[1].pause)
    }

    @Test
    fun originalOffsetSkipsCollapsedWhitespace() {
        val source = "Раз,  два  три"
        assertEquals(source.indexOf("два"), originalOffset(source, 0, source.length, "Раз, ".length))
        assertEquals(source.indexOf("три"), originalOffset(source, 0, source.length, "Раз, два ".length))
    }

    @Test
    fun bookSourceFindsTheSentenceUnderAReadingPosition() {
        val chapters = listOf(
            Chapter("Первая", listOf("Один. Два.", "Три.")),
            Chapter("Вторая", listOf("Четыре. Пять.")),
        )
        val source = BookSegmentSource(chapters, firstChapter = 0)
        val atStart = source.indexOf(0, 0, 0)
        assertEquals(-1, source.get(atStart)!!.paragraph)
        val second = source.get(source.indexOf(0, 0, 7))!!
        assertEquals("Два.", second.text)
        val nextChapter = source.get(source.indexOf(1, 0, 8))!!
        assertEquals(1, nextChapter.chapter)
        assertEquals("Пять.", nextChapter.text)
        assertNull(source.get(1000))
    }

    @Test
    fun readingPositionRoundTrips() {
        val paragraphs = listOf("Первый абзац текста.", "Второй абзац подлиннее, с запятыми и точками.", "Третий.")
        val progress = chapterProgressOf(paragraphs, 1, 10)
        val book = Book(title = "t", format = "txt", chapters = listOf(Chapter("c", paragraphs)), chapterProgress = progress)
        assertEquals(1 to 10, book.position())
    }

    @Test
    fun resamplingPreservesPitch() {
        val input = FloatArray(24_000) { index -> (0.5 * sin(2 * PI * 440.0 * index / 24_000)).toFloat() }
        val output = Resampler.resample(input, 24_000, 44_100)
        assertTrue(abs(output.size - 44_100) <= 2)
        // Count zero crossings: 440 Hz gives about 880 per second at any sample rate.
        val crossings = (1 until output.size).count { (output[it - 1] < 0f) != (output[it] < 0f) }
        assertTrue("crossings=$crossings", abs(crossings - 880) <= 6)
        val peak = output.drop(500).dropLast(500).maxOf { abs(it) }
        assertTrue("peak=$peak", peak in 0.47f..0.53f)
    }

    @Test
    fun silenceIsTrimmedButSpeechKept() {
        val rate = 24_000
        val samples = FloatArray(rate) { index ->
            if (index in 6_000 until 18_000) (0.3 * sin(2 * PI * 200.0 * index / rate)).toFloat() else 0f
        }
        val trimmed = AudioShaping.trimSilence(samples, rate)
        assertTrue(trimmed.size in 12_000..14_000)
        assertEquals(0, AudioShaping.trimSilence(FloatArray(1_000), rate).size)
    }

    @Test
    fun bookSourceGrowsWithAFetchedChapter() {
        val source = BookSegmentSource(listOf(Chapter("Первая", listOf("Раз. Два."))), firstChapter = 0)
        val first = generateSequence(0) { it + 1 }.takeWhile { source.get(it) != null }.count()
        assertNull(source.get(first))
        var asked = 0
        source.onExhausted = { asked++ }
        source.growing = true
        assertTrue(source.mayGrow())
        assertTrue(source.mayGrow())
        assertEquals("the next chapter is requested once", 1, asked)
        source.append(Chapter("Вторая", listOf("Три.")))
        source.growing = false
        assertEquals(1, source.get(first)?.chapter)
        assertEquals("Три.", source.get(first + 1)?.text)
    }

    @Test
    fun speechBoundsMatchTrimmedAudio() {
        val rate = 24_000
        val samples = FloatArray(rate) { index ->
            if (index in 6_000 until 18_000) (0.3 * sin(2 * PI * 200.0 * index / rate)).toFloat() else 0f
        }
        val bounds = AudioShaping.speechBounds(samples, rate)
        assertEquals(AudioShaping.trimSilence(samples, rate).size, bounds.last - bounds.first + 1)
        assertTrue(bounds.first in 5_000..6_000)
    }

    @Test
    fun engineWordsLandOnParagraphOffsetsAndOutputFrames() {
        // The engine got the whitespace-collapsed sentence; the paragraph has a double space and a lead-in.
        val paragraph = "Вдох.  Он  остановился у окна."
        val segment = splitForSpeech(paragraph, "ru")[1].let { chunk ->
            SpeechSegment(0, 0, chunk.start, chunk.end, chunk.text, "ru", SegmentPause.PARAGRAPH)
        }
        assertEquals("Он остановился у окна.", segment.text)
        val words = listOf(
            WordMark(frame = 8_820, start = 3, end = 14), // «остановился», listed out of order
            WordMark(frame = 4_410, start = 0, end = 2), // «Он»
            WordMark(frame = 9_000, start = 30, end = 40), // outside the text: dropped
        )
        val placed = placeWords(words, trimmedFrames = 4_410, sourceRate = 44_100, outputRate = 24_000,
            outputLength = 20_000, source = paragraph, segment = segment)
        assertEquals(2, placed.size)
        assertEquals(WordMark(0, 7, 9), placed[0])
        assertEquals("Он", paragraph.substring(placed[0].start, placed[0].end))
        assertEquals(2_400, placed[1].frame)
        assertEquals("остановился", paragraph.substring(placed[1].start, placed[1].end))
    }

    @Test
    fun wavReaderDecodesSixteenBitPcm() {
        val samples = shortArrayOf(0, 16_384, -16_384, 32_767)
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
        }.array()
        val wav = ByteArrayOutputStream().apply {
            fun int(value: Int) = write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            fun short(value: Int) = write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
            write("RIFF".toByteArray()); int(36 + data.size); write("WAVE".toByteArray())
            write("fmt ".toByteArray()); int(16); short(1); short(1); int(22_050); int(44_100); short(2); short(16)
            write("data".toByteArray()); int(data.size); write(data)
        }.toByteArray()
        val file = File.createTempFile("tts", ".wav")
        try {
            file.writeBytes(wav)
            val audio = WavReader.read(file)
            assertEquals(22_050, audio.sampleRate)
            assertEquals(4, audio.samples.size)
            assertEquals(0.5f, audio.samples[1], 0.001f)
            assertEquals(-0.5f, audio.samples[2], 0.001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun voiceChoicesSurviveStorage() {
        val choices = listOf(
            VoiceChoice(VoiceEngine.SUPERTONIC, 7),
            VoiceChoice(VoiceEngine.KOKORO, 21),
            VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE, voiceName = "aidar-ru"),
            VoiceChoice(VoiceEngine.SYSTEM, enginePackage = "com.google.android.tts", voiceName = ""),
        )
        choices.forEach { assertEquals(it, VoiceChoice.decode(it.encode())) }
        assertNull(VoiceChoice.decode("garbage"))
        assertEquals("en-gb", VoiceCatalog.kokoroLanguage(21))
        assertEquals("en-us", VoiceCatalog.kokoroLanguage(3))
    }
}
