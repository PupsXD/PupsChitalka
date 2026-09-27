package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextChunkerTest {
    @Test
    fun russianSentencesKeepOriginalOffsetsAfterSpaceNormalization() {
        val source = "  Он\u00a0остановился у окна.  За стеклом медленно падал снег.  "
        val chunks = splitForSpeech(source, "ru")

        assertEquals(2, chunks.size)
        assertEquals("Он остановился у окна.", chunks[0].text)
        assertEquals("За стеклом медленно падал снег.", chunks[1].text)
        assertEquals("Он\u00a0остановился у окна.", source.substring(chunks[0].start, chunks[0].end))
        assertEquals("За стеклом медленно падал снег.", source.substring(chunks[1].start, chunks[1].end))
    }

    @Test
    fun longSentenceWithNonbreakingSpacesSplitsBetweenWords() {
        val words = List(20) { "путешественник" }
        val source = words.joinToString("\u00a0") + "."
        val chunks = splitForSpeech(source, "ru", maxChars = 48)

        assertTrue(chunks.size > 1)
        assertTrue(chunks.all { it.text.length <= 48 })
        assertTrue(chunks.zipWithNext().all { (a, b) -> a.end < b.start })
        assertEquals(words.joinToString(" ") + ".", chunks.joinToString(" ") { it.text })
        assertTrue(chunks.all { !it.text.startsWith(" ") && !it.text.endsWith(" ") })
    }

    @Test
    fun duplicateSentencesPointToTheirOwnOccurrence() {
        val source = "Повтори это. Повтори это."
        val chunks = splitForSpeech(source, "ru")

        assertEquals(2, chunks.size)
        assertEquals("Повтори это.", source.substring(chunks[0].start, chunks[0].end))
        assertEquals("Повтори это.", source.substring(chunks[1].start, chunks[1].end))
        assertTrue(chunks[1].start > chunks[0].end)
    }
}
