package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** The homograph layer: a reading is chosen only when the neighbouring words name it. */
class RussianHomographStressTest {
    private fun marked(text: String) = RussianHomographStress.apply(text)

    @Test
    fun aCastleIsNotALock() {
        assertEquals("На холме стоял средневековый з+амок.", marked("На холме стоял средневековый замок."))
        assertEquals("Он повесил на дверь зам+ок.", marked("Он повесил на дверь замок."))
        // No sign of either reading: the model's dictionary keeps its answer.
        assertEquals("Замок был большой.", marked("Замок был большой."))
    }

    @Test
    fun aPriceIsNotAStandingPerson() {
        assertEquals("Сколько ст+оит эта книга?", marked("Сколько стоит эта книга?"))
        assertEquals("Ему не ст+оит соглашаться.", marked("Ему не стоит соглашаться."))
        assertEquals("Он сто+ит у окна.", marked("Он стоит у окна."))
        assertEquals("Он не сто+ит на месте.", marked("Он не стоит на месте."))
        assertEquals("Эту книгу ст+оит посмотреть.", marked("Эту книгу стоит посмотреть."))
    }

    @Test
    fun tormentIsNotFlour() {
        assertEquals("Пшеничная мук+а лежала в мешке.", marked("Пшеничная мука лежала в мешке."))
        assertEquals("Это была страшная м+ука.", marked("Это была страшная мука."))
        assertEquals("Мука лежала на полке.", marked("Мука лежала на полке."))
    }

    @Test
    fun inflectedFormsPlaceStressOnTheirActualVowel() {
        assertEquals("\u0414\u0432\u0435\u0440\u044c \u0437\u0430\u043c\u043a+\u0430.", marked("\u0414\u0432\u0435\u0440\u044c \u0437\u0430\u043c\u043a\u0430."))
        assertEquals("\u041a \u043a\u043b\u044e\u0447\u0443 \u0437\u0430\u043c\u043a+\u0443.", marked("\u041a \u043a\u043b\u044e\u0447\u0443 \u0437\u0430\u043c\u043a\u0443."))
        assertEquals("\u0421\u0442\u0435\u043d\u044b \u0441\u0442\u0430\u0440\u0438\u043d\u043d\u043e\u0433\u043e \u0437+\u0430\u043c\u043a\u0430.", marked("\u0421\u0442\u0435\u043d\u044b \u0441\u0442\u0430\u0440\u0438\u043d\u043d\u043e\u0433\u043e \u0437\u0430\u043c\u043a\u0430."))
        assertEquals("\u041a \u0441\u0442\u0430\u0440\u0438\u043d\u043d\u043e\u043c\u0443 \u0437+\u0430\u043c\u043a\u0443.", marked("\u041a \u0441\u0442\u0430\u0440\u0438\u043d\u043d\u043e\u043c\u0443 \u0437\u0430\u043c\u043a\u0443."))
        assertEquals("\u041f\u0448\u0435\u043d\u0438\u0447\u043d\u043e\u0439 \u043c\u0443\u043a+\u043e\u0439.", marked("\u041f\u0448\u0435\u043d\u0438\u0447\u043d\u043e\u0439 \u043c\u0443\u043a\u043e\u0439."))
        assertEquals("\u0421\u0442\u0440\u0430\u0448\u043d\u043e\u0439 \u043c+\u0443\u043a\u043e\u0439.", marked("\u0421\u0442\u0440\u0430\u0448\u043d\u043e\u0439 \u043c\u0443\u043a\u043e\u0439."))
    }

    @Test
    fun theReadersOwnStressIsNeverOverwritten() {
        assertEquals("На двери висел зам+ок.", marked("На двери висел зам+ок."))
        assertEquals("Сколько ст+оит эта книга?", marked("Сколько ст+оит эта книга?"))
    }

    @Test
    fun aWordWithNoHomographIsLeftAlone() {
        assertEquals("Он открыл дверь и вошёл в дом.", marked("Он открыл дверь и вошёл в дом."))
        assertEquals("", marked(""))
    }
}
