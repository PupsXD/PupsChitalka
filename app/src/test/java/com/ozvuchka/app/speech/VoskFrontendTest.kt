package com.ozvuchka.app.speech

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The Kotlin frontend against vosk-tts 0.3.61 itself: src/test/resources/vosk/frontend-cases.json
 * holds, for each sentence, what the Python code produced (token ids, the BERT rows it keeps, the five
 * phoneme streams and the BERT row of every phoneme), with the real ruBert vocabulary and the lines of
 * the 0.10 dictionary for the words used.
 */
class VoskFrontendTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun resource(name: String) = javaClass.getResource("/vosk/$name")!!.readText()

    private fun frontend(): VoskFrontend {
        val dictionaryFile = folder.newFile("dictionary").apply { writeText(resource("dictionary-sample.txt")) }
        val index = File(folder.root, "dictionary.idx")
        VoskDictionary.buildIndex(dictionaryFile, index)
        val dictionary = VoskDictionary.open(dictionaryFile, index)
        val map = JSONObject(resource("config.json")).getJSONObject("phoneme_id_map")
        val ids = map.keys().asSequence().associateWith { map.getInt(it) }
        return VoskFrontend(ids, BertWordPiece.load(resource("vocab.txt").lineSequence()), dictionary::find)
    }

    @Test
    fun matchesThePythonFrontendSentenceBySentence() {
        val frontend = frontend()
        val cases = JSONArray(resource("frontend-cases.json"))
        assertTrue(cases.length() >= 12)
        for (i in 0 until cases.length()) {
            val case = cases.getJSONObject(i)
            val raw = case.getString("raw")
            assertEquals(raw, case.getString("text"), VoskFrontend.sanitize(raw))
            val prepared = frontend.prepare(raw)
            assertArrayEquals(raw, longs(case.getJSONArray("token_ids")), prepared.tokenIds)
            assertArrayEquals(raw, ints(case.getJSONArray("selected")), prepared.wordTokens)
            val phonemes = case.getJSONArray("phonemes")
            assertEquals(raw, phonemes.length(), prepared.phonemes.size)
            for (p in 0 until phonemes.length()) {
                assertArrayEquals("$raw, phoneme $p", ints(phonemes.getJSONArray(p)), prepared.phonemes[p])
            }
            assertArrayEquals(raw, ints(case.getJSONArray("bert_rows")), prepared.bertRows)
        }
    }

    @Test
    fun aWordTheModelCannotSayIsDroppedInsteadOfCrashing() {
        // vosk-tts raises KeyError('o') on «Word»: no dictionary entry, and Latin letters have no phonemes.
        val prepared = frontend().prepare("Он открыл Word и закрыл окно.")
        assertTrue(prepared.phonemes.isNotEmpty())
        assertTrue(prepared.bertRows.all { it in prepared.wordTokens.indices })
    }

    @Test
    fun stressMarksChooseTheVowel() {
        assertEquals("z a0 m o1 k", VoskFrontend.convert("зам+ок"))
        assertEquals("z a1 m o0 k", VoskFrontend.convert("з+амок"))
        // a soft sign softens the consonant and disappears; «е» after it gets its «j»
        assertEquals("pj j e1 s", VoskFrontend.convert("пь+ес"))
    }

    @Test
    fun sanitizingKeepsOnlyWhatTheModelKnows() {
        assertEquals("\"Тише...\" - сказал он", VoskFrontend.sanitize("«Тише…» — сказал он"))
        assertEquals("Д'Артаньян", VoskFrontend.sanitize("Д’Артаньян"))
        assertEquals("в году", VoskFrontend.sanitize("в 1987 году №"))
    }

    @Test
    fun dictionaryKeepsTheMostProbableVariantAndTheFirstOnATie() {
        val text = folder.newFile("small").apply {
            writeText(
                """
                абв 0.5 a0 b v
                абв 0.9 a1 b v
                абв 0.9 a0 b1 v
                где 1 gj d e1
                яма 0.2 j a1 m a0
                """.trimIndent() + "\n",
            )
        }
        val index = File(folder.root, "small.idx")
        VoskDictionary.buildIndex(text, index)
        val dictionary = VoskDictionary.open(text, index)
        assertEquals("a1 b v", dictionary.find("абв"))
        assertEquals("gj d e1", dictionary.find("где"))
        assertEquals("j a1 m a0", dictionary.find("яма"))
        assertNull(dictionary.find("аб"))
        assertNull(dictionary.find("абвг"))
        assertNull(dictionary.find("жук"))
    }

    @Test
    fun anUnsortedDictionaryIsRefused() {
        val text = folder.newFile("unsorted").apply { writeText("где 1 g\nабв 1 a\n") }
        try {
            VoskDictionary.buildIndex(text, File(folder.root, "unsorted.idx"))
            fail("an unsorted dictionary must not be indexed")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("not sorted"))
        }
    }

    private fun ints(array: JSONArray) = IntArray(array.length()) { array.getInt(it) }
    private fun longs(array: JSONArray) = LongArray(array.length()) { array.getLong(it) }
}
