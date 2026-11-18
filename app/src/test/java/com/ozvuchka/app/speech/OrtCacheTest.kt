package com.ozvuchka.app.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The .ort copy is only valid for the model it was built from: a replaced model must rebuild it, not
 * load a stale graph. The check is pure file bookkeeping, so it runs without ONNX Runtime.
 */
class OrtCacheTest {
    @Test
    fun acceptsACopyBuiltFromTheSameModel() {
        val directory = tempDirectory()
        val model = File(directory, "model-fast.onnx").apply { writeText("model") }
        val cache = File(model.path + ".ort").apply { writeText("optimized") }
        val stamp = File(model.path + ".ort.stamp")
        OrtCache.remember(model, stamp)

        assertTrue(OrtCache.isValid(model, cache, stamp))
    }

    @Test
    fun rejectsACopyAfterTheModelChanged() {
        val directory = tempDirectory()
        val model = File(directory, "model-fast.onnx").apply { writeText("model") }
        val cache = File(model.path + ".ort").apply { writeText("optimized") }
        val stamp = File(model.path + ".ort.stamp")
        OrtCache.remember(model, stamp)

        // The same path, another graph: as when the model is replaced by hand or by an update.
        model.writeText("another model")
        model.setLastModified(1_000_000_000_000)

        assertFalse(OrtCache.isValid(model, cache, stamp))
    }

    @Test
    fun rejectsACopyWithoutAStamp() {
        val directory = tempDirectory()
        val model = File(directory, "model-fast.onnx").apply { writeText("model") }
        val cache = File(model.path + ".ort").apply { writeText("optimized") }

        // A copy written before stamps existed cannot be tied to the model.
        assertFalse(OrtCache.isValid(model, cache, File(model.path + ".ort.stamp")))
    }

    @Test
    fun rejectsACopyOfAModelThatIsGone() {
        val directory = tempDirectory()
        val model = File(directory, "model-fast.onnx")
        val cache = File(directory, "model-fast.onnx.ort").apply { writeText("optimized") }
        val stamp = File(directory, "model-fast.onnx.ort.stamp").apply { writeText("234:567") }

        assertFalse(OrtCache.isValid(model, cache, stamp))
    }

    private fun tempDirectory(): File = Files.createTempDirectory("ort-cache-").toFile().apply { deleteOnExit() }
}
