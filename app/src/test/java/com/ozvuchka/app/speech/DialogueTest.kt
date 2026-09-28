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
        // A common name tells by itself; one worn by both sexes does not.
        assertEquals(SpeechRole.FEMALE, speakerGender("said Mary.", "en"))
        assertNull(speakerGender("said Sam.", "en"))
        assertEquals(SpeechRole.FEMALE, speakerGender("сказала Марина Сергеевна.", "ru"))
        assertEquals(SpeechRole.MALE, speakerGender("говорит Семён Аркадьевич.", "ru"))
        assertEquals(SpeechRole.MALE, speakerGender("her father asked without looking up.", "en"))
    }

    @Test
    fun pastTenseTellsTheGenderButNounsDoNot() {
        assertEquals(SpeechRole.FEMALE, pastGender("сказала"))
        assertEquals(SpeechRole.FEMALE, pastGender("замерла"))
        assertEquals(SpeechRole.FEMALE, pastGender("пришла"))
        assertEquals(SpeechRole.FEMALE, pastGender("начала"))
        assertEquals(SpeechRole.MALE, pastGender("сказал"))
        assertEquals(SpeechRole.MALE, pastGender("произнес"))
        assertEquals(SpeechRole.MALE, pastGender("осекся"))
        assertEquals(SpeechRole.MALE, pastGender("улыбнулся"))
        assertNull(pastGender("стола"))
        assertNull(pastGender("генерал"))
        assertNull(pastGender("мол"))
        assertNull(pastGender("сказали"))
        assertNull(pastGender("вырвалось"))
    }

    @Test
    fun authorsWordsNameTheSpeakerNotEveryoneAround() {
        fun gender(words: String, attribution: Boolean = true) = subjectClue(words, "ru", Cast.EMPTY, attribution)?.gender
        // The verb is far from the dash behind a long name or a title.
        assertEquals(SpeechRole.MALE, gender("Лань Си Чэнь покачал головой."))
        assertEquals(SpeechRole.MALE, gender("мрачно произнёс Лань Цижэнь."))
        assertEquals(SpeechRole.FEMALE, gender("госпожа Юй Цзыюань холодно оглядела собравшихся."))
        // Someone else in the same words does not count.
        assertEquals(SpeechRole.MALE, gender("сказал Иван, и она отвернулась."))
        assertEquals(SpeechRole.FEMALE, gender("Маша, глядя на его руки, покачала головой."))
        // A voice, a hand or «у неё» tell whose they are.
        assertEquals(SpeechRole.FEMALE, gender("Её голос зазвенел от гнева.", attribution = false))
        assertEquals(SpeechRole.FEMALE, gender("у неё дрогнул голос."))
        assertEquals(SpeechRole.FEMALE, gender("вырвалось у неё."))
        assertEquals(SpeechRole.MALE, gender("его рука легла ей на плечо."))
        // A new sentence needs someone doing it.
        assertNull(gender("Повисла тишина.", attribution = false))
        assertEquals(SpeechRole.FEMALE, gender("У стойки стояла Вэнь Цин.", attribution = false))
        assertNull(gender("Братья переглянулись.", attribution = false))
        // Silence and answers.
        assertEquals(true, subjectClue("Она ничего не ответила.", "ru", Cast.EMPTY, attribution = false)?.silent)
        assertEquals(true, subjectClue("огрызнулся тот.", "ru", Cast.EMPTY, attribution = true)?.reply)
    }

    @Test
    fun theSpeakersOwnWordsTellTheGender() {
        assertEquals(SpeechRole.FEMALE, selfGender("Я пришла домой около девяти.", "ru"))
        assertEquals(SpeechRole.FEMALE, selfGender("Я, кажется, опоздала.", "ru"))
        assertEquals(SpeechRole.FEMALE, selfGender("Я бы тоже не отказалась.", "ru"))
        assertEquals(SpeechRole.FEMALE, selfGender("Я так рада тебя видеть!", "ru"))
        assertEquals(SpeechRole.FEMALE, selfGender("Сама знаю.", "ru"))
        assertEquals(SpeechRole.FEMALE, selfGender("Видела я таких!", "ru"))
        assertEquals(SpeechRole.MALE, selfGender("Я за всю зиму ни разу не видел противника.", "ru"))
        assertEquals(SpeechRole.MALE, selfGender("Я совсем не такой.", "ru"))
        assertNull(selfGender("Я думаю, что он пришёл.", "ru"))
        // Someone else's words quoted in the line.
        assertNull(selfGender("А он мне: «Я пришёл, открывай».", "ru"))
        assertNull(selfGender("I was there.", "en"))
    }

    @Test
    fun aLineTellsWhomItSpeaksTo() {
        assertEquals(SpeechRole.FEMALE, addresseeGender("Сестрица, ты тоже идёшь с нами?", "ru", Cast.EMPTY))
        assertEquals(SpeechRole.MALE, addresseeGender("Ты даже не спросил, что я думаю.", "ru", Cast.EMPTY))
        assertEquals(SpeechRole.FEMALE, addresseeGender("Не надо, шицзе.", "ru", Cast.EMPTY))
        assertEquals(SpeechRole.FEMALE, addresseeGender("Госпожа Юй, не стоит решать сгоряча.", "ru", Cast.EMPTY))
        assertNull(addresseeGender("Мама звонила. Три раза.", "ru", Cast.EMPTY))
        assertEquals(SpeechRole.MALE, addresseeGender("Dad, wait for me!", "en", Cast.EMPTY))
    }

    @Test
    fun linesAfterTheAuthorsWordsAndStoriesOverSeveralParagraphs() {
        assertEquals(
            listOf("Сюэ Ян рассмеялся." to false, "Даочжан, ты слишком серьёзен!" to true),
            parts("Сюэ Ян рассмеялся. — Даочжан, ты слишком серьёзен!"),
        )
        assertEquals(listOf("А-Цин фыркнула:" to false, "Оба хороши." to true), parts("А-Цин фыркнула: — Оба хороши."))
        // «Москва — столица»: a dash inside narration is not a line, nor is the author's own question.
        assertNull(parts("Москва — столица, и в ней всегда шумно."))
        assertNull(parts("Кто виноват? — Никто. Так уж вышло."))
        assertEquals(listOf("Пойдём" to true, "Он встал." to false), parts("«Пойдём». — Он встал."))
        // A quote left open goes on in the next paragraph.
        assertEquals(listOf("Было это давно, ещё до войны." to true), parts("«Было это давно, ещё до войны."))
        assertEquals(listOf("Every autumn we picked apples." to true), parts("“Every autumn we picked apples.", "en"))
    }

    @Test
    fun namesLearnTheirGenderFromTheBook() {
        val cast = Cast.learn(
            listOf(
                "— Спускайся, — отвечает Лань Ванцзи.",
                "Вэнь Цин выходит во двор. Она кутается в плащ.",
                "Лань Ванцзи стоит внизу. Он не любит высоту.",
                "Маша стояла у калитки, а Цзян Яньли кивнула ей.",
                "— Нет, — сказал Цзян Чэн.",
                "Mary put down her cup.",
            ),
        )
        assertEquals(SpeechRole.FEMALE, cast.genderOf("Вэнь Цин"))
        assertEquals(SpeechRole.MALE, cast.genderOf("Лань Ванцзи"))
        assertEquals(SpeechRole.FEMALE, cast.genderOf("Цзян Яньли"))
        assertEquals(SpeechRole.MALE, cast.genderOf("Цзян Чэн"))
        // A family name shared by a brother and a sister tells nothing.
        assertNull(cast.genderOf("Цзян"))
        // Case forms: «голос Маши».
        assertEquals(SpeechRole.FEMALE, cast.genderOf("Маши"))
        assertEquals(SpeechRole.FEMALE, cast.genderOf("Mary’s"))
        // The reader's choice wins.
        assertEquals(SpeechRole.MALE, cast.withChoices(mapOf("Вэнь Цин" to SpeechRole.MALE)).genderOf("Вэнь Цин"))
    }

    @Test
    fun theListOfCharactersJoinsTheCasesOfAName() {
        val cast = Cast.learn(
            listOf(
                "Маша стояла у калитки.", "— Нет, — сказала Маша.", "Маша улыбнулась.",
                "Он посмотрел на Машу.", "Голос Маши дрогнул.", "— Да, — кивнул Лань Чжань.",
            ),
        )
        val members = cast.members(minMentions = 1).associate { it.name to it.mentions }
        assertEquals(5, members["Маша"])
        assertNull(members["Машу"])
        assertEquals(SpeechRole.FEMALE, cast.members(minMentions = 1).first { it.name == "Маша" }.gender)
    }

    @Test
    fun aWordFindsItsCharacter() {
        val members = listOf(
            CastMember("Лань Ванцзи", SpeechRole.MALE, 40),
            CastMember("Лань Сичэнь", SpeechRole.MALE, 20),
            CastMember("Маша", SpeechRole.FEMALE, 10),
        )
        assertEquals("Лань Сичэнь", members.memberFor("Лань", "— Брат, — кивнул Лань Сичэнь.")?.name)
        assertEquals("Лань Ванцзи", members.memberFor("Ванцзи", "Ванцзи молчал.")?.name)
        assertEquals("Маша", members.memberFor("Маши", "Голос Маши дрогнул.")?.name)
        assertNull(members.memberFor("Лань", "Лань пила из ручья."))
        assertNull(members.memberFor("и", "Маша и Лань Ванцзи"))
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
    fun aLineWithoutAuthorsWordsBelongsToWhoActedBeforeIt() {
        val result = roles(
            "— Ты вернёшься? — спросила она почти шёпотом.",
            "— Конечно, — ответил он, не оборачиваясь. — К утру всё закончится.",
            "Она долго смотрела ему вслед.",
            "— Я буду ждать.",
        )
        assertEquals("Я буду ждать." to SpeechRole.FEMALE, result.last())
        val english = roles(
            "“Are you coming back?” she whispered.",
            "“Of course,” he said without turning around.",
            "She watched him go for a long time.",
            "“I will wait.”",
        )
        assertEquals("I will wait." to SpeechRole.FEMALE, english.last())
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
    fun narrationMenAndWomenSoundApart() {
        fun ruVoice(name: String) = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE, voiceName = name)
        val aidar = ruVoice("aidar")
        val baya = ruVoice("baya")
        val eugene = ruVoice("eugene")
        val xenia = ruVoice("xenia")
        val options = listOf(
            RoleVoice(aidar, "Aidar", SpeechRole.MALE),
            RoleVoice(baya, "Baya", SpeechRole.FEMALE),
            RoleVoice(eugene, "Eugene", SpeechRole.MALE),
            RoleVoice(xenia, "Xenia", SpeechRole.FEMALE),
        )
        // RuVoice reads «По умолчанию» with Aidar, and the men's roles had Aidar too: they move to Eugene.
        val same = DialogueVoices(DialogueMode.BY_GENDER, male = aidar, female = xenia)
        assertEquals(listOf(SpeechRole.MALE), same.sameAsNarrator(aidar))
        val apart = same.withDistinctVoices(options, narrator = aidar)
        assertEquals(eugene, apart.male)
        assertEquals(xenia, apart.female)
        assertEquals(emptyList<SpeechRole>(), apart.sameAsNarrator(aidar))
        // A woman narrates: the women's roles get another woman's voice.
        val fresh = DialogueVoices(DialogueMode.BY_GENDER).withDistinctVoices(options, narrator = baya)
        assertEquals(aidar, fresh.male)
        assertEquals(xenia, fresh.female)
        // One voice for every line, never the narrator's.
        assertEquals(baya, DialogueVoices(DialogueMode.SINGLE).withDistinctVoices(options, narrator = aidar).single)
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
