package com.ozvuchka.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RussianStressLexiconTest {
    private val lexicon = RussianStressLexicon(File("src/main/assets/ru_stress.bin").readBytes())

    @Test
    fun includesUnambiguousFormsButLeavesHomographsAndYoRestorationToRuVoice() {
        assertEquals("алфав+ит", lexicon.find("алфавит"))
        assertEquals("позвон+ил", lexicon.find("позвонил"))
        assertEquals("катал+ог", lexicon.find("каталог"))
        assertNull(lexicon.find("звонит"))
        assertNull(lexicon.find("замок"))
        assertNull(lexicon.find("мука"))
        assertNull(lexicon.find("все"))
        assertNull(lexicon.find("елка"))
    }

    @Test
    fun readerPronunciationsWinAndDefaultsReachOnlyRuVoice() {
        val dictionary = PronunciationDictionary(mapOf("каталог" to "кат+алог"), lexicon::find)
        assertEquals("Кат+алог и алфав+ит", dictionary.apply("Каталог и алфавит", StressStyle.PLUS).text)
        assertEquals("Ката́лог и алфавит", dictionary.apply("Каталог и алфавит", StressStyle.ACUTE).text)
        assertEquals("Каталог и алфавит", dictionary.apply("Каталог и алфавит", StressStyle.NONE).text)
        assertEquals("алфавит", dictionary.apply("алфавит", StressStyle.ACUTE).text)
    }
}
