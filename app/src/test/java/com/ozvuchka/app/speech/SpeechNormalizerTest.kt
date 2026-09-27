package com.ozvuchka.app.speech

import com.ozvuchka.app.speech.RussianNumbers.Case
import com.ozvuchka.app.speech.RussianNumbers.Gender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechNormalizerTest {
    private fun ru(text: String) = SpeechNormalizer.normalize(text, "ru")
    private fun en(text: String) = SpeechNormalizer.normalize(text, "en")

    @Test
    fun russianCardinalsAndOrdinals() {
        assertEquals("тысяча девятьсот семнадцать", RussianNumbers.cardinal(1917))
        assertEquals("две тысячи двадцать четыре", RussianNumbers.cardinal(2024))
        assertEquals("двадцать одна", RussianNumbers.cardinal(21, Gender.FEMININE))
        assertEquals("миллион двести тысяч", RussianNumbers.cardinal(1_200_000))
        assertEquals("тысяча девятьсот семнадцатом", RussianNumbers.ordinal(1917, Case.PREPOSITIONAL))
        assertEquals("двухтысячный", RussianNumbers.ordinal(2000, Case.NOMINATIVE))
        assertEquals("третья", RussianNumbers.ordinal(3, Case.NOMINATIVE, Gender.FEMININE))
        assertEquals("сороковой", RussianNumbers.ordinal(40, Case.NOMINATIVE))
        assertEquals("девяностых", RussianNumbers.ordinal(90, Case.GENITIVE, plural = true))
        assertEquals("двух", RussianNumbers.cardinalGenitive(2))
        assertEquals("двадцати пяти", RussianNumbers.cardinalGenitive(25))
        assertEquals(14, RussianNumbers.parseRoman("XIV"))
        assertEquals(null, RussianNumbers.parseRoman("IIII"))
    }

    @Test
    fun russianYearsDatesAndTimes() {
        assertEquals("в тысяча девятьсот семнадцатом году", ru("в 1917 году"))
        assertEquals("в тысяча девятьсот семнадцатом году началась", ru("в 1917 г. началась"))
        assertEquals("пятого декабря две тысячи двадцатого года", ru("05.12.2020"))
        assertEquals("в двадцать один тридцать", ru("в 21:30"))
        assertEquals("прошло три года", ru("прошло 3 года"))
        assertEquals("в девяностых годах", ru("в 90-х годах"))
        assertEquals("для двух человек", ru("для 2-х человек"))
    }

    @Test
    fun russianRomanNumeralsAndHeadings() {
        assertEquals("Глава четвёртая", ru("Глава IV"))
        assertEquals("в двадцатом веке", ru("в XX веке"))
        assertEquals("Пётр первый", ru("Пётр I"))
        assertEquals("при Николае втором", ru("при Николае II"))
        assertEquals("Глава первая. Начало", ru("Глава 1. Начало"))
    }

    @Test
    fun russianAgreementUnitsAndAbbreviations() {
        assertEquals("одна книга и две минуты", ru("1 книга и 2 минуты"))
        assertEquals("два часа", ru("2 часа"))
        assertEquals("пять километров", ru("5 км"))
        assertEquals("двадцать один процент", ru("21%"))
        assertEquals("три целых пять десятых", ru("3,5"))
        assertEquals("то есть так далее", ru("т. е. т. д."))
        assertEquals("номер пять", ru("№5"))
        assertEquals("город Москва", ru("г. Москва"))
    }

    @Test
    fun supertonicTextDropsDialogueDashesAndQuotes() {
        val spoken = SpeechNormalizer.normalize("— «Привет», — сказал он.", "ru", forSupertonic = true)
        assertEquals("Привет, сказал он.", spoken)
    }

    @Test
    fun englishNumbers() {
        assertEquals("nineteen eighty-four", en("1984"))
        assertEquals("two thousand five", en("2005"))
        assertEquals("the twenty-first century", en("the 21st century"))
        assertEquals("Mister Smith paid twenty dollars", en("Mr. Smith paid $20"))
        assertEquals("ten thirty", en("10:30"))
        assertEquals("three point one four", en("3.14"))
        assertEquals("the nineteen nineties", en("the 1990s"))
    }

    @Test
    fun emojiAndFootnotesAreRemoved() {
        val spoken = ru("Он улыбнулся 😀[12] и ушёл*.")
        assertFalse(spoken.any { Character.isSurrogate(it) })
        assertTrue(spoken, !spoken.contains("[") && !spoken.contains("*"))
    }
}
