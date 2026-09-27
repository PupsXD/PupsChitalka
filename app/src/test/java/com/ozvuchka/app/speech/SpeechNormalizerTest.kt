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
        assertEquals("к двадцати одному тридцати", ru("к 21:30"))
        assertEquals("до семи ноль пяти", ru("до 7:05"))
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
    fun russianNumeralsAgreeInCase() {
        assertEquals("с двух чашек кофе и одной книги", ru("с 2 чашек кофе и 1 книги"))
        assertEquals("с пяти до семи часов", ru("с 5 до 7 часов"))
        assertEquals("между тремя домами", ru("между 3 домами"))
        assertEquals("в пяти домах", ru("в 5 домах"))
        assertEquals("к двадцати пяти годам", ru("к 25 годам"))
        assertEquals("с двадцатью одним другом", ru("с 21 другом"))
        assertEquals("около трёх километров", ru("около 3 км"))
        assertEquals("более двадцати одного процента", ru("более 21%"))
        assertEquals("около тысячи двухсот рублей", ru("около 1200 рублей"))
        assertEquals("в пять часов", ru("в 5 часов"))
        assertEquals("по два рубля", ru("по 2 рубля"))
        assertEquals("тремястами", RussianNumbers.cardinal(300, RussianNumbers.Gender.MASCULINE, Case.INSTRUMENTAL))
    }

    @Test
    fun russianOrdinalsFromNounForm() {
        assertEquals("на третьем этаже", ru("на 3 этаже"))
        assertEquals("в пятом классе", ru("в 5 классе"))
        assertEquals("в двадцать первом веке", ru("в 21 веке"))
        assertEquals("с пятой страницы", ru("с 5 страницы"))
        assertEquals("ученик пятого класса", ru("ученик 5 класса"))
        assertEquals("в десятом часу", ru("в 10 часу"))
        assertEquals("на третью минуту", ru("на 3 минуту"))
        assertEquals("во вторую группу", ru("в 2 группу"))
        assertEquals("во второй части", ru("во 2 части"))
        assertEquals("ко второму числу", ru("к 2 числу"))
        assertEquals("на седьмой ступени", ru("на 7 ступени"))
        assertEquals("в пятом издании", ru("в 5 издании"))
        assertEquals("на втором месте", ru("на 2 месте"))
        assertEquals("в пятьдесят седьмой школе", ru("в 57 школе"))
    }

    @Test
    fun russianOrdinalsInOtherCases() {
        assertEquals("за второй партой", ru("за 2 партой"))
        assertEquals("под третьим номером", ru("под 3 номером"))
        assertEquals("на второй путь", ru("на 2 путь"))
        assertEquals("на третий день", ru("на 3 день"))
    }

    @Test
    fun russianDatesDecadesAndScores() {
        assertEquals("она родилась двенадцатого апреля", ru("она родилась 12 апреля"))
        assertEquals("к двенадцатому апреля", ru("к 12 апреля"))
        assertEquals("с первого по пятое мая", ru("с 1 по 5 мая"))
        assertEquals("в девяностые годы", ru("в 90-е годы"))
        assertEquals("в тысяча девятьсот девяностые и двухтысячные", ru("в 1990-е и 2000-е"))
        assertEquals("двадцатое число", ru("20-е число"))
        assertEquals(
            "в тысяча девятьсот сорок первом — тысяча девятьсот сорок пятом годах",
            ru("в 1941—1945 гг."),
        )
        assertEquals("счёт три — два в пользу хозяев", ru("счёт 3:2 в пользу хозяев"))
    }

    @Test
    fun russianCardinalPhrasesStayCardinal() {
        assertEquals("он купил двадцать одну книгу", ru("он купил 21 книгу"))
        assertEquals("помогли одному человеку", ru("помогли 1 человеку"))
        assertEquals("в два ночи", ru("в 2 ночи"))
        assertEquals("в час ночи", ru("в 1 ночи"))
        assertEquals("в двух домах и одной квартире", ru("в 2 домах и 1 квартире"))
        assertEquals("по одному рублю", ru("по 1 рублю"))
        assertEquals("в пять часов", ru("в 5 часов"))
        assertEquals("в два раза", ru("в 2 раза"))
        assertEquals("на три части", ru("на 3 части"))
        assertEquals("раз в две недели", ru("раз в 2 недели"))
        assertEquals("два класса", ru("2 класса"))
        assertEquals("в одном доме", ru("в 1 доме"))
        assertEquals("на одну неделю", ru("на 1 неделю"))
        assertEquals("через двадцать одну минуту", ru("через 21 минуту"))
        assertEquals("об одной книге", ru("о 1 книге"))
        assertEquals("со ста рублей", ru("с 100 рублей"))
        assertEquals("в десять утра", ru("в 10 утра"))
    }

    @Test
    fun supertonicDashAfterPunctuationIsDropped() {
        val spoken = SpeechNormalizer.normalize("— Ты вернёшься? — спросила она почти шёпотом.", "ru", forSupertonic = true)
        assertEquals("Ты вернёшься? спросила она почти шёпотом.", spoken)
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
