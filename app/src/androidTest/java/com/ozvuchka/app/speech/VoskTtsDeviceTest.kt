package com.ozvuchka.app.speech

import android.os.Bundle
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The Kotlin Vosk engine on the phone, end to end, against vosk-tts in Python. Needs the model in
 * the app's speech_models/vosk-model-tts-ru-0.10-multi and bench/ref-28.f32: what Python's frontend
 * and its 5-step decoder (tools/voices/vosk-fewer-steps.py --steps 5) give for [SENTENCE], voice 28,
 * without noise (scales 0, 1, 0). With model.onnx present the decoder is first rewritten on the phone.
 *   adb shell am instrument -w -e class com.ozvuchka.app.speech.VoskTtsDeviceTest com.ozvuchka.app.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class VoskTtsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val dir = SpeechModels.directory(context, SpeechModel.VOSK)

    @Test
    fun speaksLikeThePythonEngine() {
        assumeTrue("no Vosk model in $dir", File(dir, "dictionary").isFile)
        val index = File(dir, "dictionary.idx")
        if (!index.isFile) {
            val started = System.nanoTime()
            VoskDictionary.buildIndex(File(dir, "dictionary"), index)
            report("dictionary index: ${ms(started)} ms, ${index.length() / 1024} KB")
        }
        val original = File(dir, "model.onnx")
        if (original.isFile) {
            // What installing does: the 5-step decoder written on the phone, replacing any copy from a PC.
            val started = System.nanoTime()
            VoskDecoderSteps.halve(original, File(dir, "model-5steps.onnx"))
            report("decoder rewritten on the phone: ${ms(started)} ms")
        }
        var started = System.nanoTime()
        val tts = VoskTts(dir, threads = 4)
        report("load ${VoskTts.acousticModel(dir).name}: ${ms(started)} ms")
        try {
            started = System.nanoTime()
            val audio = tts.synthesize(SENTENCE, speaker = 28, noise = 0f, durationNoise = 0f)
            report("exact run: ${audio.size} samples in ${ms(started)} ms")
            val reference = File(dir, "bench/ref-28.f32").readBytes().let { bytes ->
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                FloatArray(buffer.remaining()).also { buffer.get(it) }
            }
            val n = minOf(audio.size, reference.size)
            var dot = 0.0; var a2 = 0.0; var b2 = 0.0; var maxDiff = 0f
            for (i in 0 until n) {
                dot += audio[i] * reference[i]; a2 += audio[i] * audio[i]; b2 += reference[i] * reference[i]
                maxDiff = maxOf(maxDiff, abs(audio[i] - reference[i]))
            }
            val correlation = dot / sqrt(a2 * b2)
            report("against Python: samples ${audio.size} vs ${reference.size}, correlation %.5f, max difference %.4f".format(correlation, maxDiff))
            assertTrue("length differs: ${audio.size} vs ${reference.size}", abs(audio.size - reference.size) <= 256)
            assertTrue("correlation $correlation", correlation > 0.99)

            // The reading speed of a real passage with the usual noise, sentence by sentence.
            var seconds = 0.0
            started = System.nanoTime()
            val out = ArrayList<FloatArray>()
            for (sentence in PASSAGE) {
                val samples = tts.synthesize(sentence, speaker = 28)
                seconds += samples.size / tts.sampleRate.toDouble()
                out += samples
            }
            val spent = (System.nanoTime() - started) / 1e9
            report("passage: %.1f s of audio in %.1f s, RTF %.3f".format(seconds, spent, spent / seconds))
            File(context.getExternalFilesDir(null), "vosk-passage.raw").outputStream().use { stream ->
                val buffer = ByteBuffer.allocate(4 * out.sumOf { it.size }).order(ByteOrder.LITTLE_ENDIAN)
                out.forEach { samples -> samples.forEach { buffer.putFloat(it) } }
                stream.write(buffer.array())
            }
        } finally {
            tts.close()
        }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun report(line: String) {
        Log.i("VoskDevice", line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "VoskDevice: $line\n") })
    }

    private companion object {
        const val SENTENCE = "Дункан усмехнулся, но рука его всё ещё лежала на рукояти."
        val PASSAGE = listOf(
            "Дункан долго смотрел на туман за кормой.",
            "— Ты слышишь? — спросил он вполголоса.",
            "— Ничего там нет, капитан! — проворчал боцман. — Третий день один и тот же туман.",
            "Дункан усмехнулся, но рука его всё ещё лежала на рукояти.",
            "— Тогда почему все молчат? Проверь замок на нижней палубе и доложи мне к восьми часам.",
        )
    }
}
