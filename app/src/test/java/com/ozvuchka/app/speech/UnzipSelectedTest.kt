package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Vosk download is one zip, laid out as alphacephei.com publishes vosk-model-tts-ru-0.10-multi.zip. */
class UnzipSelectedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val files = (SpeechModel.VOSK.source as ModelSource.Zip).files

    private fun zip(vararg entries: Pair<String, String>): File = folder.newFile("model.zip").also { file ->
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.encodeToByteArray())
                out.closeEntry()
            }
        }
    }

    @Test
    fun keepsTheListedFilesUnderTheirOwnNames() {
        val root = "vosk-model-tts-ru-0.10-multi"
        val archive = zip(
            "$root/" to "",
            "$root/README.md" to "readme",
            "$root/model.onnx" to "acoustic",
            "$root/bert/" to "",
            "$root/bert/model.onnx" to "bert",
            "$root/bert/README.md" to "bert readme",
            "$root/bert/vocab.txt" to "[PAD]",
            "$root/dictionary" to "абв 1 a0 b v",
            "$root/config.json" to "{}",
            "../outside.txt" to "must not be written",
        )
        val staging = folder.newFolder("staging")
        var progress = 0L
        unzipSelected(archive, files, staging) { progress = it }
        assertEquals(setOf("model.onnx", "bert.onnx", "vocab.txt", "dictionary", "config.json"), staging.list()!!.toSet())
        assertEquals("acoustic", File(staging, "model.onnx").readText())
        assertEquals("bert", File(staging, "bert.onnx").readText())
        assertFalse(File(folder.root, "outside.txt").exists())
        assertTrue(progress > 0)
    }

    @Test
    fun aMissingFileFailsTheInstall() {
        val archive = zip("vosk-model-tts-ru-0.10-multi/model.onnx" to "acoustic")
        try {
            unzipSelected(archive, files, folder.newFolder("staging"))
            fail("an incomplete archive must not install")
        } catch (expected: IllegalStateException) {
            assertTrue(expected.message!!.contains("bert.onnx"))
        }
    }
}
