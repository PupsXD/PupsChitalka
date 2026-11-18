package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The rewriter on the real Vosk 0.10 model when one is given — it is 235 MB and not in the repository:
 *   VOSK_MODEL=<…/model.onnx> VOSK_OUT=<…/model-5steps.onnx> gradlew :app:testDebugUnitTest --tests '*VoskDecoderStepsTest*'
 * tools/voices/vosk-check-steps.py then compares the result with the Python rewrite of the same graph.
 *
 * The rest of the tests build a graph shaped like Vosk's decoder by hand, so the strict layout checks
 * are covered without the model: ten guided steps of dt = 0.1 whose x ends in a vocoder node, with the
 * dt constants either as Constant nodes (the model as published) or as initializers (after
 * onnxruntime's quant_pre_process).
 */
class VoskDecoderStepsTest {
    @Test
    fun rewritesTheRealModel() {
        val source = System.getenv("VOSK_MODEL")?.let(::File)
        assumeTrue("VOSK_MODEL is not set", source?.isFile == true)
        val target = File(System.getenv("VOSK_OUT") ?: (source!!.path + ".5steps.onnx"))
        val added = VoskDecoderSteps.rewrite(source!!, target)
        assertTrue(target.length() > source.length() / 2)
        assertTrue("added $added constants", added in 1..10)
    }

    @Test
    fun keepsEightStepsWithoutGuidance() {
        val source = decoderGraph()
        val target = tempFile()
        assertEquals(2, VoskDecoderSteps.rewrite(source, target, steps = 8, cfgSteps = 0))
        val written = target.readBytes().toString(Charsets.ISO_8859_1)
        // Steps 3 and 8 cover two original steps each; no other kept step gets a new dt.
        assertTrue(written.contains("dt_3_x2"))
        assertTrue(written.contains("dt_8_x2"))
        for (step in listOf(0, 1, 2, 5, 6, 7)) assertTrue(!written.contains("dt_${step}_x2"))
        // Without guidance only the conditional estimator runs: every uncond velocity is unreachable.
        for (step in listOf(0, 1, 2, 3, 5, 6, 7, 8)) {
            assertTrue("conditional estimator $step kept", written.contains(velocity(step * 2)))
            assertTrue("uncond estimator $step dropped", !written.contains(velocity(step * 2 + 1)))
        }
        // Steps 4 and 9 are gone with both of their estimator runs.
        for (step in listOf(4, 9)) {
            assertTrue(!written.contains(velocity(step * 2)))
            assertTrue(!written.contains(velocity(step * 2 + 1)))
        }
        assertTrue(target.length() < source.length())
    }

    @Test
    fun keepsFiveGuidedStepsByDefault() {
        val target = tempFile()
        assertEquals(5, VoskDecoderSteps.rewrite(decoderGraph(), target))
        val written = target.readBytes().toString(Charsets.ISO_8859_1)
        for (step in listOf(0, 2, 4, 6, 8)) assertTrue(written.contains("dt_${step}_x2"))
        for (step in listOf(1, 3, 5, 7, 9)) assertTrue(!written.contains(velocity(step * 2)))
        // Guidance is kept on every step that is left, so both estimator runs of it stay.
        for (step in listOf(0, 2, 4, 6, 8)) assertTrue(written.contains(velocity(step * 2 + 1)))
    }

    @Test
    fun guidesEveryKeptStepWhenStepsAreGivenAlone() {
        // cfgSteps defaults to steps, as --steps 8 does in vosk-fewer-steps.py and vosk-prepare.py:
        // 8 kept steps, all guided, are 16 estimator runs.
        val target = tempFile()
        assertEquals(2, VoskDecoderSteps.rewrite(decoderGraph(), target, steps = 8))
        val written = target.readBytes().toString(Charsets.ISO_8859_1)
        for (step in listOf(0, 1, 2, 3, 5, 6, 7, 8)) {
            assertTrue("conditional estimator $step kept", written.contains(velocity(step * 2)))
            assertTrue("uncond estimator $step kept", written.contains(velocity(step * 2 + 1)))
        }
        for (step in listOf(4, 9)) {
            assertTrue(!written.contains(velocity(step * 2)))
            assertTrue(!written.contains(velocity(step * 2 + 1)))
        }
    }

    @Test
    fun keepsGuidanceOnTheFirstKeptStepsOnly() {
        // 8 steps with 3 guided: the measured variant, 11 estimator runs.
        val target = tempFile()
        assertEquals(2, VoskDecoderSteps.rewrite(decoderGraph(), target, steps = 8, cfgSteps = 3))
        val written = target.readBytes().toString(Charsets.ISO_8859_1)
        for (step in listOf(0, 1, 2)) {
            assertTrue("conditional estimator $step kept", written.contains(velocity(step * 2)))
            assertTrue("uncond estimator $step kept", written.contains(velocity(step * 2 + 1)))
        }
        for (step in listOf(3, 5, 6, 7, 8)) {
            assertTrue("conditional estimator $step kept", written.contains(velocity(step * 2)))
            assertTrue("uncond estimator $step dropped", !written.contains(velocity(step * 2 + 1)))
        }
    }

    @Test
    fun rewiresConsecutiveSkippedSteps() {
        // 4 steps keep 0, 2, 5, 7; steps 8 and 9 are dropped in a row, so the vocoder must read x_8,
        // the x step 7 started its update from, rather than x_10 that no node writes any more.
        val target = tempFile()
        assertEquals(4, VoskDecoderSteps.rewrite(decoderGraph(), target, steps = 4))
        val written = target.readBytes().toString(Charsets.ISO_8859_1)
        val vocoder = node("vocoder", "Mul", listOf("x_8", "gain"), listOf("wav")).toString(Charsets.ISO_8859_1)
        assertTrue(written.contains(vocoder))
        for (step in listOf(0, 2, 5, 7)) assertTrue(written.contains(velocity(step * 2 + 1)))
        for (step in listOf(1, 3, 4, 6, 8, 9)) {
            assertTrue(!written.contains(velocity(step * 2)))
            assertTrue(!written.contains(velocity(step * 2 + 1)))
        }
    }

    @Test
    fun readsDtFromInitializers() {
        val target = tempFile()
        assertEquals(2, VoskDecoderSteps.rewrite(decoderGraph(constantsAsInitializers = true), target, steps = 8, cfgSteps = 0))
        assertTrue(target.readBytes().toString(Charsets.ISO_8859_1).contains("dt_3_x2"))
    }

    @Test
    fun sharesOneDtNodeBetweenStepsThatUseTheSameConstant() {
        // Vosk's model reuses dt constants between steps; both renames must point at one node.
        assertEquals(1, VoskDecoderSteps.rewrite(decoderGraph(sharedDt = true), tempFile()))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAGraphWithNineSteps() {
        VoskDecoderSteps.rewrite(decoderGraph(steps = 9), tempFile())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAGraphWithAnotherDt() {
        VoskDecoderSteps.rewrite(decoderGraph(dt = 0.05f), tempFile())
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsAGraphWithoutVoskNames() {
        VoskDecoderSteps.rewrite(decoderGraph(names = false), tempFile())
    }

    private fun tempFile(): File = File.createTempFile("vosk-", ".onnx").apply { deleteOnExit() }

    private fun velocity(estimator: Int): String =
        if (estimator == 0) "/matcha/decoder/estimator/Mul_1_output_0"
        else "/matcha/decoder/estimator_$estimator/Mul_1_output_0"

    /**
     * A graph shaped like Vosk's decoder: x flows through [steps] update nodes, each fed by a scaled
     * guided velocity, and then into a vocoder node that is the only graph output — which is why the
     * last decoder step can be left out.
     */
    private fun decoderGraph(
        steps: Int = 10,
        dt: Float = 0.1f,
        names: Boolean = true,
        constantsAsInitializers: Boolean = false,
        sharedDt: Boolean = false,
    ): File {
        val nodes = ArrayList<ByteArray>()
        val initializers = ArrayList<ByteArray>()
        var x = "x"
        for (p in 0 until steps) {
            val cond = if (names) velocity(2 * p) else "v_cond_$p"
            val uncond = if (names) velocity(2 * p + 1) else "v_uncond_$p"
            val suffix = if (p == 0) "" else "_$p"
            val dtName = if (sharedDt) "dt_all" else "dt_$p"
            nodes += node("/matcha/decoder/Sub$suffix", "Sub", listOf(cond, uncond), listOf("sub_$p"))
            nodes += node("w_$p", "Mul", listOf("sub_$p", "half"), listOf("w_$p"))
            nodes += node("mix_$p", "Add", listOf(cond, "w_$p"), listOf("mix_$p"))
            nodes += node("s_$p", "Mul", listOf("mix_$p", dtName), listOf("s_$p"))
            nodes += node("u_$p", "Add", listOf(x, "s_$p"), listOf("x_${p + 1}"))
            if (constantsAsInitializers) initializers += tensor(dtName, dt) else nodes += constantNode(dtName, dt)
            x = "x_${p + 1}"
        }
        nodes += node("vocoder", "Mul", listOf(x, "gain"), listOf("wav"))
        val graph = ByteArrayOutputStream().apply {
            nodes.forEach { writeField(1, it) }               // GraphProto.node
            initializers.forEach { writeField(5, it) }        // GraphProto.initializer
            writeField(12, valueInfo("wav"))                  // GraphProto.output
        }.toByteArray()
        val model = ByteArrayOutputStream().apply { writeField(7, graph) }.toByteArray() // ModelProto.graph
        return tempFile().apply { writeBytes(model) }
    }

    private fun valueInfo(name: String): ByteArray = ByteArrayOutputStream().apply {
        writeField(1, name.encodeToByteArray())
    }.toByteArray()

    private fun node(name: String, opType: String, inputs: List<String>, outputs: List<String>): ByteArray =
        ByteArrayOutputStream().apply {
            inputs.forEach { writeField(1, it.encodeToByteArray()) }
            outputs.forEach { writeField(2, it.encodeToByteArray()) }
            writeField(3, name.encodeToByteArray())
            writeField(4, opType.encodeToByteArray())
        }.toByteArray()

    private fun constantNode(output: String, value: Float): ByteArray = ByteArrayOutputStream().apply {
        writeField(2, output.encodeToByteArray())
        writeField(4, "Constant".encodeToByteArray())
        writeField(5, attribute(value))
    }.toByteArray()

    private fun attribute(value: Float): ByteArray = ByteArrayOutputStream().apply {
        writeField(1, "value".encodeToByteArray())
        writeField(5, tensor("", value))
        writeTag(20, 0)
        writeVarint(4) // AttributeProto.type = TENSOR
    }.toByteArray()

    private fun tensor(name: String, value: Float): ByteArray = ByteArrayOutputStream().apply {
        if (name.isNotEmpty()) writeField(8, name.encodeToByteArray())
        writeTag(2, 0)
        writeVarint(1) // TensorProto.data_type = FLOAT
        writeField(9, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
    }.toByteArray()

    private fun ByteArrayOutputStream.writeTag(number: Int, wireType: Int) = writeVarint((number.toLong() shl 3) or wireType.toLong())

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            write(((v and 0x7f) or 0x80).toInt())
            v = v ushr 7
        }
        write(v.toInt())
    }

    private fun ByteArrayOutputStream.writeField(number: Int, value: ByteArray) {
        writeTag(number, 2)
        writeVarint(value.size.toLong())
        write(value)
    }
}
