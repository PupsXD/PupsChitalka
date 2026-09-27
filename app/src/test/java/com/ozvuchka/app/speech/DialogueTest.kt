package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialogueTest {
    private fun parts(text: String, language: String = "ru") =
        dialogueParts(text, language)?.map { text.substring(it.start, it.end) to it.speech }

    private fun roles(vararg paragraphs: String, language: String = "ru") =
        chapterSegments(0, null, paragraphs.toList(), splitDialogue = true).map { it.text to it.role }

    @Test
    fun russianDashDialogueAlternatesSpeechAndAuthor() {
        assertEquals(
            listOf("Ты вернёшься?" to true, "спросила она почти шёпотом." to false),
            parts("— Ты вернёшься? — спросила она почти шёпотом."),
        )
        assertEquals(
            listOf("Конечно," to true, "ответил он, не оборачиваясь." to false, "К утру всё закончится." to true),
            parts("— Конечно, — ответил он, не оборачиваясь. — К утру всё закончится."),
        )
        // A dash inside a line that follows a word is punctuation, not a change of speaker.
        assertEquals(listOf("Москва — столица." to true), parts("— Москва — столица."))
    }

    @Test
    fun quotedSpeechButNotTitles() {
        assertEquals(
            listOf("Он сказал:" to false, "Пойдём домой" to true, "Она кивнула." to false),
            parts("Он сказал: «Пойдём домой». Она кивнула."),
        )
        assertEquals(
            listOf("Привет" to true, "сказал он." to false),
            parts("«Привет», — сказал он."),
        )
        assertNull(parts("Он прочитал «Войну и мир» за неделю."))
        assertEquals(
            listOf("Are you coming back?" to true, "she whispered." to false),
            parts("“Are you coming back?” she whispered.", "en"),
        )
        assertNull(parts("He read “The Hobbit” twice.", "en"))
        assertNull(parts("Обычный абзац без реплик."))
    }

    @Test
    fun speakerGenderComesFromTheAttribution() {
        assertEquals(SpeechRole.FEMALE, speakerGender("спросила она почти шёпотом.", "ru"))
        assertEquals(SpeechRole.MALE, speakerGender("ответил он, не оборачиваясь.", "ru"))
        assertEquals(SpeechRole.FEMALE, speakerGender("Маша улыбнулась.", "ru"))
        assertEquals(SpeechRole.MALE, speakerGender("пожал плечами Иван.", "ru"))
        assertEquals(SpeechRole.FEMALE, speakerGender("she whispered.", "en"))
        assertNull(speakerGender("said Mary.", "en"))
        assertEquals(SpeechRole.MALE, speakerGender("her father asked without looking up.", "en"))
    }

    @Test
    fun linesWithoutAttributionFollowTheConversation() {
        val result = roles(
            "— Привет, — сказал Иван.",
            "— Здравствуй, — ответила Маша.",
            "— Как дела?",
            "— Хорошо.",
        )
        assertEquals(
            listOf(
                "Привет," to SpeechRole.MALE, "сказал Иван." to SpeechRole.NARRATOR,
                "Здравствуй," to SpeechRole.FEMALE, "ответила Маша." to SpeechRole.NARRATOR,
                "Как дела?" to SpeechRole.MALE,
                "Хорошо." to SpeechRole.FEMALE,
            ),
            result,
        )
    }

    @Test
    fun dialogueSplittingIsOptional() {
        val plain = chapterSegments(0, null, listOf("— Ты вернёшься? — спросила она почти шёпотом."))
        assertEquals(setOf(SpeechRole.NARRATOR), plain.map { it.role }.toSet())
        val split = chapterSegments(0, null, listOf("— Ты вернёшься? — спросила она почти шёпотом."), splitDialogue = true)
        assertEquals(SegmentPause.TURN, split.first().pause)
        assertEquals(SegmentPause.CHAPTER_END, split.last().pause)
    }

    @Test
    fun dialogueVoicesPickByRole() {
        val narrator = VoiceChoice(VoiceEngine.SUPERTONIC, 0)
        val male = VoiceChoice(VoiceEngine.SUPERTONIC, 5)
        val female = VoiceChoice(VoiceEngine.SUPERTONIC, 1)
        val byGender = DialogueVoices(DialogueMode.BY_GENDER, male = male, female = female)
        assertEquals(male, byGender.voiceFor(SpeechRole.MALE, narrator))
        assertEquals(female, byGender.voiceFor(SpeechRole.FEMALE, narrator))
        assertEquals(narrator, byGender.voiceFor(SpeechRole.SPEECH, narrator))
        assertEquals(narrator, byGender.voiceFor(SpeechRole.NARRATOR, narrator))
        val single = DialogueVoices(DialogueMode.SINGLE, single = female)
        assertEquals(female, single.voiceFor(SpeechRole.SPEECH, narrator))
        assertEquals(narrator, DialogueVoices().voiceFor(SpeechRole.FEMALE, narrator))
    }

    @Test
    fun pronunciationsRewriteWordsForEachEngine() {
        val dictionary = PronunciationDictionary(mapOf("замок" to "з+амок", "Гермиона" to "Герми+она"))
        val text = "Замок стоял. Гермиона ждала."
        val plus = dictionary.apply(text, StressStyle.PLUS)
        assertEquals("З+амок стоял. Герми+она ждала.", plus.text)
        assertEquals("За́мок стоял. Гермио́на ждала.", dictionary.apply(text, StressStyle.ACUTE).text)
        assertEquals(text, dictionary.apply(text, StressStyle.NONE).text)
        // Word ranges in the rewritten text land on the book's words.
        val stood = plus.text.indexOf("стоял")
        assertEquals(text.indexOf("стоял")..text.indexOf("стоял") + 4, plus.originalRange(stood, stood + 5))
        assertEquals(0..4, plus.originalRange(0, 6))
        assertEquals("за́мок", PronunciationDictionary.display("з+амок"))
        assertEquals("з+амок", PronunciationDictionary.stressed("Замок", 1))
        assertEquals(listOf(1, 3), PronunciationDictionary.vowelIndices("замок"))
        assertEquals(text, PronunciationDictionary.EMPTY.apply(text, StressStyle.PLUS).text)
    }
}
