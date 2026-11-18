package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Which installs can gain Vosk's fast graph by downloading model-prepared.onnx alone. An install made
 * before that graph keeps model-5steps.onnx (or model.onnx if the rewrite had failed); a fresh one has
 * model-fast.onnx already and must never be touched.
 */
class VoskInstallTest {
    @Test
    fun absentWhenNothingIsInstalled() {
        val directory = tempDirectory()

        assertEquals(VoskInstallKind.ABSENT, voskInstallKind(directory))
    }

    @Test
    fun absentWithoutTheDictionaryIndex() {
        val directory = installWith("model-fast.onnx")
        File(directory, "dictionary.idx").delete()

        assertEquals(VoskInstallKind.ABSENT, voskInstallKind(directory))
    }

    @Test
    fun legacyWithTheFiveStepGraph() {
        val directory = installWith("model-5steps.onnx")

        assertEquals(VoskInstallKind.LEGACY, voskInstallKind(directory))
    }

    @Test
    fun legacyWithThePublishedGraph() {
        val directory = installWith("model.onnx")

        assertEquals(VoskInstallKind.LEGACY, voskInstallKind(directory))
    }

    @Test
    fun legacyWithThePreparedGraphOfAFailedRewrite() {
        val directory = installWith("model-prepared.onnx")

        assertEquals(VoskInstallKind.LEGACY, voskInstallKind(directory))
    }

    @Test
    fun fastWithTheRewrittenGraph() {
        // The old 5-step file may still lie next to it after an upgrade: model-fast.onnx wins.
        val directory = installWith("model-fast.onnx", "model-5steps.onnx")

        assertEquals(VoskInstallKind.FAST, voskInstallKind(directory))
    }

    private fun installWith(vararg graphs: String): File {
        val directory = tempDirectory()
        SpeechModel.VOSK.requiredFiles.forEach { File(directory, it).writeText("file") }
        graphs.forEach { File(directory, it).writeText("graph") }
        return directory
    }

    private fun tempDirectory(): File = Files.createTempDirectory("vosk-install-").toFile().apply { deleteOnExit() }
}
