package com.ozvuchka.app.speech

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Rewrites the real Vosk 0.10 model when one is given; the model is 235 MB and not in the repository.
 *   VOSK_MODEL=<…/model.onnx> VOSK_OUT=<…/model-5steps.onnx> gradlew :app:testDebugUnitTest --tests '*VoskDecoderStepsTest*'
 * tools/voices/vosk-check-steps.py then compares the result with the Python rewrite.
 */
class VoskDecoderStepsTest {
    @Test
    fun halvesTheRealModel() {
        val source = System.getenv("VOSK_MODEL")?.let(::File)
        assumeTrue("VOSK_MODEL is not set", source?.isFile == true)
        val target = File(System.getenv("VOSK_OUT") ?: (source!!.path + ".5steps.onnx"))
        VoskDecoderSteps.halve(source!!, target)
        assertTrue(target.length() > source.length() / 2)
    }
}
