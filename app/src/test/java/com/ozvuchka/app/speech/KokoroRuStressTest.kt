package com.ozvuchka.app.speech

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Russian stress for the Russian Kokoro: the reader's dictionary puts a combining acute on the vowel,
 * and espeak-ng inside sherpa-onnx reads that mark through the model's own dictionary — the same
 * convention as RUAccent in ru_g2p.py, which marks «+» before the vowel and is then converted the
 * same way. Words the dictionary does not know keep espeak's own stress rules.
 */
class KokoroRuStressTest {
    private val lexicon = RussianStressLexicon(File("src/main/assets/ru_stress.bin").readBytes())
    private val dictionary = PronunciationDictionary(emptyMap(), lexicon::find)

    @Test
    fun theRussianKokoroIsGivenAcuteStressMarks() {
        assertEquals(StressStyle.DICTIONARY_ACUTE, stressStyleFor(VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.SVETA)))
        // Vosk keeps its own dictionary, so only the reader's words are marked there.
        assertEquals(StressStyle.MARKED, stressStyleFor(VoiceChoice(VoiceEngine.VOSK, 28)))
        assertEquals(StressStyle.PLUS, stressStyleFor(VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE)))
        assertEquals(StressStyle.NONE, stressStyleFor(VoiceChoice(VoiceEngine.SUPERTONIC, 0)))
        assertEquals(StressStyle.NONE, stressStyleFor(VoiceChoice(VoiceEngine.KOKORO, 3)))
    }

    @Test
    fun knownWordsGetTheirStressAndUnknownWordsKeepEspeaksOwn() {
        assertEquals("Катало́г и алфави́т", dictionary.apply("Каталог и алфавит", StressStyle.DICTIONARY_ACUTE).text)
        // Homographs and words RUAccent leaves out stay plain: espeak-ng decides them from context.
        assertEquals("Звонит и замок", dictionary.apply("Звонит и замок", StressStyle.DICTIONARY_ACUTE).text)
        // A word the reader stressed himself wins over the dictionary.
        val own = PronunciationDictionary(mapOf("каталог" to "кат+алог"), lexicon::find)
        assertEquals("Ката́лог", own.apply("Каталог", StressStyle.DICTIONARY_ACUTE).text)
        // Nothing is marked twice when the text already carries an acute.
        assertEquals("Алфави́т", dictionary.apply("Алфави́т", StressStyle.DICTIONARY_ACUTE).text)
        assertEquals(1, dictionary.apply("Алфави́т", StressStyle.DICTIONARY_ACUTE).text.count { it == '\u0301' })
    }

    @Test
    fun theMarkSurvivesNormalization() {
        val marked = dictionary.apply("Каталог и алфавит, 3 км", StressStyle.DICTIONARY_ACUTE).text
        val spoken = SpeechNormalizer.normalize(marked, "ru")
        assertTrue(spoken.contains("Катало́г"))
        assertTrue(spoken.contains("алфави́т"))
        // Numbers still become words around the marks.
        assertTrue(spoken.contains("километра"))
        assertFalse(spoken.contains("3"))
    }
}
