package com.ozvuchka.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderTextTest {
    private val paragraphs = listOf("Он остановился у окна. За стеклом кто-нибудь шёл.")

    @Test
    fun longPressFindsTheWordAndItsSentence() {
        val window = wordTargetAt(paragraphs, 0, paragraphs[0].indexOf("окна") + 2, "ru")!!
        assertEquals("окна", window.word)
        assertEquals("Он остановился у окна.", window.sentence)
        assertEquals(0, window.sentenceStart)

        // Just past a word, the word before is meant.
        val afterWord = wordTargetAt(paragraphs, 0, paragraphs[0].indexOf("остановился") + "остановился".length, "ru")!!
        assertEquals("остановился", afterWord.word)

        val joined = wordTargetAt(paragraphs, 0, paragraphs[0].indexOf("нибудь"), "ru")!!
        assertEquals("кто-нибудь", joined.word)
        assertEquals("За стеклом кто-нибудь шёл.", joined.sentence)
        assertEquals(paragraphs[0].indexOf("За"), joined.sentenceStart)

        assertNull(wordTargetAt(paragraphs, 3, 0, "ru"))
    }
}
