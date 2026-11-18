package com.ozvuchka.app.speech

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Everything about the Russian Kokoro that can be checked without the 330 MB download: the vocabulary
 * sherpa-onnx is given, the metadata it reads, the files the install leaves behind, and the voices.
 *
 * The reference `tokens.txt` next to `config.json` was written by Python (scripts in the task
 * document, ru_g2p.py's NORMALIZE applied to espeak-ng's Russian symbols), and the same file loaded in
 * sherpa-onnx 1.13.8 synthesized Russian speech; this test keeps the Kotlin builder on that output.
 */
class KokoroRuTest {
    private fun resource(name: String): String = File("src/test/resources/kokoro-ru/$name").readText()
    private val config = resource("config.json")
    private val reference = parseTokens(resource("tokens.txt"))

    private fun parseTokens(text: String): Map<String, Int> = text.lineSequence()
        .filter { it.isNotBlank() }
        .associate { line ->
            // A token may be a space, so the id is what follows the last space.
            val cut = line.lastIndexOf(' ')
            line.substring(0, cut) to line.substring(cut + 1).trim().toInt()
        }

    @Test
    fun theVocabularyIsTheModelOwnPlusTheSymbolsEspeakEmitsForRussian() {
        val tokens = parseTokens(KokoroRuTokens.build(config))
        assertEquals(reference, tokens)
        assertEquals(114 + 4, tokens.size)
        // Kokoro never trained on espeak's hard л, stressed ё and assimilated ж/щ: they fold onto l, o, ʒ.
        assertEquals(tokens.getValue("l"), tokens.getValue("ɭ"))
        assertEquals(tokens.getValue("o"), tokens.getValue("ɵ"))
        assertEquals(tokens.getValue("ʒ"), tokens.getValue("ʐ"))
        assertEquals(tokens.getValue("ʒ"), tokens.getValue("ʑ"))
        assertTrue("ʤ" in tokens && "ə" in tokens && "ˈ" in tokens)
        // sherpa-onnx refuses a token that is not one code point, so none may be.
        tokens.keys.forEach { symbol -> assertEquals(symbol, 1, symbol.codePointCount(0, symbol.length)) }
    }

    @Test
    fun theStylePacksBecomeOneVoicesFile() {
        val directory = Files.createTempDirectory("kokoro-ru").toFile()
        try {
            val packs = KokoroRu.packs(SpeechModel.KOKORO_RU)
            assertEquals(listOf("voices/sveta.bin", "voices/masha.bin"), packs)
            packs.forEachIndexed { index, name ->
                File(directory, name).apply { parentFile.mkdirs() }.writeBytes(ByteArray(STYLE_BYTES) { index.toByte() })
            }
            File(directory, KokoroRu.CONFIG).writeText(config)
            File(directory, KokoroRu.BASE_MODEL).apply { parentFile.mkdirs() }.writeBytes(FAKE_MODEL)

            KokoroRu.prepare(directory, SpeechModel.KOKORO_RU)

            val voices = File(directory, KokoroRu.VOICES)
            assertEquals(2L * STYLE_BYTES, voices.length())
            assertArrayEquals(ByteArray(STYLE_BYTES) { 0 }, voices.readBytes().copyOfRange(0, STYLE_BYTES))
            assertArrayEquals(ByteArray(STYLE_BYTES) { 1 }, voices.readBytes().copyOfRange(STYLE_BYTES, 2 * STYLE_BYTES))
            assertEquals(reference, parseTokens(File(directory, KokoroRu.TOKENS).readText()))
            val metadata = KokoroOnnxMetadata.read(File(directory, KokoroRu.BASE_MODEL))
            assertEquals("24000", metadata["sample_rate"])
            assertEquals("2", metadata["n_speakers"])
            assertEquals("2", metadata["version"])
            assertEquals("1", metadata["has_espeak"])
            assertEquals("ru", metadata["voice"])
            assertEquals("510,1,256", metadata["style_dim"])
            // The graph itself is untouched: the fake model is still at the start of the file.
            val written = File(directory, KokoroRu.BASE_MODEL).readBytes()
            assertTrue(written.copyOf(FAKE_MODEL.size).contentEquals(FAKE_MODEL))
            assertTrue(written.size > FAKE_MODEL.size)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun dimaIsAModelOfItsOwn() {
        assertEquals(SpeechModel.KOKORO_RU_DIMA, KokoroRu.speechModelFor(KokoroRu.DIMA))
        assertEquals(SpeechModel.KOKORO_RU, KokoroRu.speechModelFor(KokoroRu.SVETA))
        assertEquals(SpeechModel.KOKORO_RU, KokoroRu.speechModelFor(KokoroRu.MASHA))
        assertEquals(listOf("voices/dima.bin"), KokoroRu.packs(SpeechModel.KOKORO_RU_DIMA))
        // sherpa-onnx numbers the speakers of a file from zero: dima is the only one in his.
        assertEquals(0, KokoroRu.localSpeaker(SpeechModel.KOKORO_RU_DIMA, KokoroRu.DIMA))
        assertEquals(1, KokoroRu.localSpeaker(SpeechModel.KOKORO_RU, KokoroRu.MASHA))
        assertTrue(KokoroRu.isMale(KokoroRu.DIMA))
        assertFalse(KokoroRu.isMale(KokoroRu.SVETA))
        assertFalse(KokoroRu.isMale(KokoroRu.MASHA))
        assertEquals(SpeechModel.KOKORO_RU_DIMA, KokoroRu.speechModelFor(VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.DIMA).speaker))
    }

    @Test
    fun theDownloadBringsEveryFileTheModelNeeds() {
        listOf(SpeechModel.KOKORO_RU, SpeechModel.KOKORO_RU_DIMA).forEach { model ->
            val source = model.source as ModelSource.Files
            assertTrue(source.baseUrl.startsWith("https://"))
            val brought = source.names + source.copiedNames + listOf(KokoroRu.VOICES, KokoroRu.TOKENS)
            model.requiredFiles.forEach { required ->
                assertTrue("${model.name}: $required", required in brought)
            }
            // The ONNX graph and the espeak-ng data Russian is phonemized with are part of the download.
            assertTrue(model.requiredFiles.any { it.endsWith(".onnx") })
            assertTrue("${KokoroRu.ESPEAK_DIR}/ru_dict" in model.requiredFiles)
            assertTrue("${KokoroRu.ESPEAK_DIR}/lang/zle/ru" in model.requiredFiles)
            assertTrue(model.sizeLabel.contains("МБ"))
            assertTrue(model.isKokoroRu)
        }
        // The male voice reuses the first download's espeak-ng data instead of fetching it again.
        assertEquals("kokoro-ru-sveta-masha", (SpeechModel.KOKORO_RU_DIMA.source as ModelSource.Files).copiedFrom)
        assertNull((SpeechModel.KOKORO_RU.source as ModelSource.Files).copiedFrom)
    }

    @Test
    fun thePickerOffersTheThreeVoicesAndKnowsTheirGender() {
        assertEquals(3, VoiceCatalog.kokoroRu.size)
        assertEquals(KokoroRu.SPEAKERS, VoiceCatalog.KOKORO_RU_SPEAKERS)
        VoiceCatalog.kokoroRu.forEach { preset ->
            assertEquals(VoiceEngine.KOKORO_RU, preset.choice.engine)
            val model = KokoroRu.speechModelFor(preset.choice.speaker)
            assertTrue("$preset", model in listOf(SpeechModel.KOKORO_RU, SpeechModel.KOKORO_RU_DIMA))
        }
        assertTrue(VoiceCatalog.kokoroRu.first { it.choice.speaker == KokoroRu.SVETA }.recommended)
        assertEquals("Kokoro RU · Свэта", VoiceCatalog.presetTitle(VoiceChoice(VoiceEngine.KOKORO_RU, KokoroRu.SVETA)))
        assertTrue(VoiceCatalog.kokoroRuIsMale(KokoroRu.DIMA))
        assertFalse(VoiceCatalog.kokoroRuIsMale(KokoroRu.MASHA))
    }

    private companion object {
        /** 510 rows of 256 float32: one style vector per phoneme count of the text. */
        const val STYLE_BYTES = KokoroRu.STYLE_ROWS * KokoroRu.STYLE_DIM * 4
        val FAKE_MODEL = byteArrayOf(0x08, 0x08, 0x3A, 0x05, 0x67, 0x72, 0x61, 0x70, 0x68)
    }
}

/** The metadata block sherpa-onnx reads, written into the end of an ONNX file that has none. */
class KokoroOnnxMetadataTest {
    @Test
    fun anOnnxFileWithoutMetadataGetsTheKeysSherpaReads() {
        val file = File.createTempFile("kokoro", ".onnx")
        try {
            file.writeBytes(onnxFile())
            assertNull(KokoroOnnxMetadata.read(file)["sample_rate"])
            assertTrue(KokoroOnnxMetadata.ensure(file, mapOf("sample_rate" to "24000", "version" to "2")))
            assertEquals(mapOf("sample_rate" to "24000", "version" to "2"), KokoroOnnxMetadata.read(file))
            // The graph that came before the appended block is untouched.
            assertTrue(file.readBytes().copyOf(onnxFile().size).contentEquals(onnxFile()))
            // A second run changes nothing: the keys are already there.
            val length = file.length()
            assertFalse(KokoroOnnxMetadata.ensure(file, mapOf("sample_rate" to "24000", "version" to "2")))
            assertEquals(length, file.length())
        } finally {
            file.delete()
        }
    }

    @Test
    fun keysTheModelAlreadyStatesAreLeftAlone() {
        val file = File.createTempFile("kokoro", ".onnx")
        try {
            file.writeBytes(onnxFile() + metadataEntry("onnx.infer", "onnxruntime.quant"))
            val existing = KokoroOnnxMetadata.read(file)
            assertEquals(mapOf("onnx.infer" to "onnxruntime.quant"), existing)
            assertTrue(KokoroOnnxMetadata.ensure(file, mapOf("voice" to "ru", "onnx.infer" to "other")))
            assertEquals("onnxruntime.quant", KokoroOnnxMetadata.read(file)["onnx.infer"])
            assertEquals("ru", KokoroOnnxMetadata.read(file)["voice"])
        } finally {
            file.delete()
        }
    }

    /** ir_version = 8 (varint) and a graph field holding the bytes "graph", as a model always has. */
    private fun onnxFile(): ByteArray = byteArrayOf(0x08, 0x08, 0x3A, 0x05, 0x67, 0x72, 0x61, 0x70, 0x68)

    private fun metadataEntry(key: String, value: String): ByteArray {
        val entry = varint(0x0A, key.toByteArray()) + varint(0x12, value.toByteArray())
        return byteArrayOf(0x72) + length(entry.size) + entry
    }

    private fun varint(tag: Int, payload: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + length(payload.size) + payload

    private fun length(value: Int): ByteArray = if (value < 128) byteArrayOf(value.toByte()) else byteArrayOf((value or 0x80).toByte(), (value shr 7).toByte())
}
