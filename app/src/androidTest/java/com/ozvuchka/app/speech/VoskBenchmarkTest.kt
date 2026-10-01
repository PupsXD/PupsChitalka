package com.ozvuchka.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * How fast Vosk TTS's two ONNX models run on the phone, on inputs prepared on a PC by the Python
 * vosk-tts frontend. Push the files first (see tools/voices/vosk-phone-bench.sh), then:
 *   adb shell am instrument -w -e class com.ozvuchka.app.speech.VoskBenchmarkTest com.ozvuchka.app.dev.test/androidx.test.runner.AndroidJUnitRunner
 * (not connectedAndroidTest: it uninstalls the app afterwards, and the model files with it).
 * Results go to logcat under the tag VoskBench and to the instrumentation status.
 */
@RunWith(AndroidJUnit4::class)
class VoskBenchmarkTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val dir = File(instrumentation.targetContext.filesDir, "vosk")

    /** Every acoustic model in the folder: model.onnx as published, s5.onnx etc. from tools/voices/vosk-fewer-steps.py. */
    @Test
    fun variants() {
        val models = dir.listFiles { file -> file.name.endsWith(".onnx") && file.name != "bert.onnx" }.orEmpty().sortedBy { it.name }
        assumeTrue("no Vosk files in $dir", models.isNotEmpty())
        for (model in models) bench("${model.name}, 4 threads", model, threads = 4)
    }

    @Test
    fun threads() {
        val model = File(dir, "s5.onnx").takeIf { it.isFile } ?: File(dir, "model.onnx")
        assumeTrue("no Vosk files in $dir", model.isFile)
        for (threads in listOf(3, 5)) bench("${model.name}, $threads threads", model, threads)
    }

    private fun bench(label: String, model: File, threads: Int) {
        val env = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        var started = System.nanoTime()
        val acoustic = env.createSession(model.path, options)
        val acousticLoad = ms(started)
        started = System.nanoTime()
        val bert = env.createSession(File(dir, "bert.onnx").path, options)
        val bertLoad = ms(started)
        report("$label: load acoustic $acousticLoad ms, bert $bertLoad ms")

        var audioSeconds = 0.0
        var spentSeconds = 0.0
        for (n in 0..2) {
            val ids = longs(File(dir, "bench/$n.ids.bin"))
            val input = longs(File(dir, "bench/$n.input.bin"))
            val embeddings = floats(File(dir, "bench/$n.bert.bin"))
            val phones = input.size / 5
            val runs = (0 until 3).map {
                val t0 = System.nanoTime()
                runBert(env, bert, ids)
                val t1 = System.nanoTime()
                val samples = runAcoustic(env, acoustic, input, embeddings, phones)
                Triple((t1 - t0) / 1e6, (System.nanoTime() - t1) / 1e6, samples)
            }
            val best = runs.drop(1).minBy { it.first + it.second } // the first run warms the kernels up
            val seconds = best.third / 22050.0
            audioSeconds += seconds
            spentSeconds += (best.first + best.second) / 1000
            report(
                "$label: #$n phones $phones audio %.2f s, bert %.0f ms, acoustic %.0f ms, RTF %.3f (first run %.0f ms)"
                    .format(seconds, best.first, best.second, (best.first + best.second) / 1000 / seconds, runs[0].first + runs[0].second),
            )
        }
        report("$label: overall RTF %.3f".format(spentSeconds / audioSeconds))
        acoustic.close()
        bert.close()
    }

    private fun runBert(env: OrtEnvironment, session: OrtSession, ids: LongArray) {
        val shape = longArrayOf(1, ids.size.toLong())
        OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape).use { inputIds ->
            OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape).use { mask ->
                OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape).use { types ->
                    session.run(mapOf("input_ids" to inputIds, "attention_mask" to mask, "token_type_ids" to types)).close()
                }
            }
        }
    }

    private fun runAcoustic(env: OrtEnvironment, session: OrtSession, input: LongArray, bert: FloatArray, phones: Int): Int {
        val tensors = listOf(
            "input" to OnnxTensor.createTensor(env, LongBuffer.wrap(input), longArrayOf(1, 5, phones.toLong())),
            "input_lengths" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(phones.toLong())), longArrayOf(1)),
            "scales" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0.8f, 1f, 0.8f)), longArrayOf(3)),
            "sid" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(28)), longArrayOf(1)),
            "bert" to OnnxTensor.createTensor(env, FloatBuffer.wrap(bert), longArrayOf(1, 768, phones.toLong())),
        )
        try {
            session.run(tensors.toMap()).use { result ->
                return (result[0] as OnnxTensor).info.shape.fold(1L) { a, b -> a * b }.toInt()
            }
        } finally {
            tensors.forEach { it.second.close() }
        }
    }

    private fun bytes(file: File): ByteBuffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
    private fun longs(file: File) = bytes(file).asLongBuffer().let { b -> LongArray(b.remaining()).also { b.get(it) } }
    private fun floats(file: File) = bytes(file).asFloatBuffer().let { b -> FloatArray(b.remaining()).also { b.get(it) } }
    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun report(line: String) {
        Log.i("VoskBench", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VoskBench: $line\n") })
    }
}
