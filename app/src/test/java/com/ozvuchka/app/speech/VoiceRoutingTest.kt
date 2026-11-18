package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which voice reads a line: a Russian Kokoro for Russian words, English Kokoro for English ones,
 * whoever the cast picks for a character. Every other engine reads both languages.
 */
class VoiceRoutingTest {
    private val sveta = VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.SVETA)
    private val dima = VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.DIMA)
    private val heart = VoiceChoice(VoiceEngine.KOKORO, 3)
    private val supertonic = VoiceChoice(VoiceEngine.SUPERTONIC, 0)
    private val ruVoice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE, voiceName = "baya-ru")

    private fun settings(
        russian: VoiceChoice = sveta,
        english: VoiceChoice = heart,
        russianDialogue: DialogueVoices = DialogueVoices(),
        englishDialogue: DialogueVoices = DialogueVoices(),
    ) = SpeechSettings(
        russianVoice = russian,
        englishVoice = english,
        speed = 1f,
        pauseScale = 1f,
        supertonicSteps = 10,
        preferFullModels = true,
        russianDialogue = russianDialogue,
        englishDialogue = englishDialogue,
    )

    @Test
    fun eachLanguageGoesToTheVoiceThatKnowsIt() {
        val settings = settings()
        assertEquals(sveta, narratorFor(settings, "ru", SpeechRole.NARRATOR))
        assertEquals(heart, narratorFor(settings, "en", SpeechRole.NARRATOR))
    }

    @Test
    fun anEnglishVoiceNeverReadsRussianWords() {
        // The English narrator is Kokoro, which knows no Russian: a Russian line goes to the Russian one.
        assertFalse(heart.speaks("ru"))
        val settings = settings(russian = ruVoice, english = heart)
        assertEquals(ruVoice, narratorFor(settings, "ru", SpeechRole.NARRATOR))
        assertTrue(ruVoice.speaks("en"))
    }

    @Test
    fun theRussianKokoroKeepsEnglishForTheEnglishVoice() {
        assertTrue(sveta.speaks("ru"))
        assertFalse(sveta.speaks("en"))
        val settings = settings(russian = sveta, english = heart)
        assertEquals(heart, narratorFor(settings, "en", SpeechRole.NARRATOR))
        assertEquals(heart, narratorFor(settings, "en", SpeechRole.MALE))
        // A cast voice that is the Russian Kokoro still yields English lines to the English narrator.
        val dialogue = DialogueVoices(DialogueMode.SINGLE, single = sveta)
        assertEquals(heart, narratorFor(settings(russianDialogue = dialogue), "en", SpeechRole.MALE))
        // With no English narrator that speaks the language, the Russian one reads it.
        assertEquals(sveta, narratorFor(settings(english = sveta), "en", SpeechRole.NARRATOR))
        assertEquals(supertonic, narratorFor(settings(russian = supertonic, english = sveta), "en", SpeechRole.NARRATOR))
    }

    @Test
    fun theCastPicksVoicesForRussianLines() {
        val dialogue = DialogueVoices(DialogueMode.BY_GENDER, male = dima, female = ruVoice)
        val settings = settings(russianDialogue = dialogue)
        assertEquals(sveta, narratorFor(settings, "ru", SpeechRole.NARRATOR))
        assertEquals(dima, narratorFor(settings, "ru", SpeechRole.MALE))
        assertEquals(ruVoice, narratorFor(settings, "ru", SpeechRole.FEMALE))
        // English lines read by the same cast settings are untouched: English has its own cast.
        assertEquals(heart, narratorFor(settings, "en", SpeechRole.MALE))
    }

    @Test
    fun everyOtherEngineReadsBothLanguages() {
        listOf(supertonic, ruVoice, VoiceChoice(VoiceEngine.VOSK, 28)).forEach { voice ->
            assertTrue(voice.encode(), voice.speaks("ru"))
            assertTrue(voice.encode(), voice.speaks("en"))
            assertEquals(voice, narratorFor(settings(russian = voice), "ru", SpeechRole.NARRATOR))
        }
    }

    @Test
    fun theRussianVoicesSurviveBeingStored() {
        listOf(sveta, dima, heart, supertonic).forEach { voice ->
            assertEquals(voice.encode(), voice, VoiceChoice.decode(voice.encode()))
        }
        assertEquals(VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.DIMA), VoiceChoice.decode("kokoroRu:2"))
        // A speaker outside the release is clamped to the last one, as Vosk and Supertonic do.
        assertEquals(VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.DIMA), VoiceChoice.decode("kokoroRu:99"))
        // An unknown engine or a missing number from an older build decodes to nothing, as before.
        assertEquals(null, VoiceChoice.decode("kokoroRu:"))
        assertEquals(null, VoiceChoice.decode("piper:1"))
    }
}
