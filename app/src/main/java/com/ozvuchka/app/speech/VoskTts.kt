package com.ozvuchka.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Vosk TTS 0.10 (Alpha Cephei, Apache 2.0): 57 Russian voices from one model. Text goes through
 * [VoskFrontend], ruBERT gives each word its context, and the acoustic model (Matcha-TTS decoder plus
 * Vocos vocoder) renders 22 050 Hz audio. Not thread-safe: the caller runs one sentence at a time.
 *
 * Files in [directory]: config.json, vocab.txt, dictionary (+ dictionary.idx, built on first use),
 * bert.onnx, and the acoustic model: model-5steps.onnx when present (half the decoder steps, see
 * tools/voices/vosk-fewer-steps.py), otherwise model.onnx as published.
 */
internal class VoskTts(directory: File, threads: Int) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val frontend: VoskFrontend
    private val bert: OrtSession
    private val acoustic: OrtSession
    private val noise: Float
    private val durationNoise: Float
    val sampleRate: Int
    val speakers: Int

    init {
        val config = JSONObject(File(directory, "config.json").readText())
        sampleRate = config.getJSONObject("audio").getInt("sample_rate")
        speakers = config.optInt("num_speakers", 1)
        val inference = config.optJSONObject("inference") ?: JSONObject()
        noise = inference.optDouble("noise_level", 0.8).toFloat()
        durationNoise = inference.optDouble("duration_noise_level", 0.8).toFloat()
        val map = config.getJSONObject("phoneme_id_map")
        val ids = map.keys().asSequence().associateWith { map.getInt(it) }
        val dictionaryFile = File(directory, "dictionary")
        val index = File(directory, "dictionary.idx")
        if (!index.isFile) VoskDictionary.buildIndex(dictionaryFile, index)
        val dictionary = VoskDictionary.open(dictionaryFile, index)
        val tokenizer = File(directory, "vocab.txt").useLines { BertWordPiece.load(it.toList().asSequence()) }
        frontend = VoskFrontend(ids, tokenizer, dictionary::find)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        bert = env.createSession(File(directory, "bert.onnx").path, options)
        acoustic = env.createSession(acousticModel(directory).path, options)
    }

    /**
     * Audio for [text]; [speed] above 1 speaks faster (the model shortens the sounds, pitch unchanged).
     * [noise] and [durationNoise] vary the voice and timing from run to run; 0 makes the output exact.
     */
    fun synthesize(
        text: String,
        speaker: Int,
        speed: Float = 1f,
        noise: Float = this.noise,
        durationNoise: Float = this.durationNoise,
    ): FloatArray {
        val prepared = frontend.prepare(text)
        if (prepared.phonemes.size <= 3) return FloatArray(0) // only ^, a space and $: nothing to say
        val words = wordEmbeddings(prepared)
        val count = prepared.phonemes.size
        val streams = LongArray(5 * count)
        val bertInput = FloatArray(HIDDEN * count)
        for (i in 0 until count) {
            for (s in 0 until 5) streams[s * count + i] = prepared.phonemes[i][s].toLong()
            val row = prepared.bertRows[i]
            for (d in 0 until HIDDEN) bertInput[d * count + i] = words[row * HIDDEN + d]
        }
        val inputs = linkedMapOf(
            "input" to OnnxTensor.createTensor(env, LongBuffer.wrap(streams), longArrayOf(1, 5, count.toLong())),
            "input_lengths" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(count.toLong())), longArrayOf(1)),
            "scales" to OnnxTensor.createTensor(
                env, FloatBuffer.wrap(floatArrayOf(noise, 1f / speed.coerceIn(0.3f, 4f), durationNoise)), longArrayOf(3),
            ),
            "sid" to OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(speaker.coerceIn(0, speakers - 1).toLong())), longArrayOf(1)),
            "bert" to OnnxTensor.createTensor(env, FloatBuffer.wrap(bertInput), longArrayOf(1, HIDDEN.toLong(), count.toLong())),
        )
        try {
            acoustic.run(inputs).use { result -> return flatten(result[0] as OnnxTensor) }
        } finally {
            inputs.values.forEach { it.close() }
        }
    }

    /** ruBERT's output rows for the word-start tokens, one after another, [HIDDEN] floats each. */
    private fun wordEmbeddings(prepared: VoskFrontend.Prepared): FloatArray {
        val ids = prepared.tokenIds
        val shape = longArrayOf(1, ids.size.toLong())
        val tensors = listOf(
            "input_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(ids), shape),
            "attention_mask" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size) { 1 }), shape),
            "token_type_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(ids.size)), shape),
        )
        try {
            bert.run(tensors.toMap()).use { result ->
                val all = flatten(result[0] as OnnxTensor) // (tokens, 768): vosk-tts indexes its rows by token
                val words = FloatArray(prepared.wordTokens.size * HIDDEN)
                prepared.wordTokens.forEachIndexed { n, token ->
                    System.arraycopy(all, token * HIDDEN, words, n * HIDDEN, HIDDEN)
                }
                return words
            }
        } finally {
            tensors.forEach { it.second.close() }
        }
    }

    private fun flatten(tensor: OnnxTensor): FloatArray {
        val buffer = tensor.floatBuffer
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    override fun close() {
        acoustic.close()
        bert.close()
    }

    companion object {
        private const val HIDDEN = 768
        val REQUIRED = listOf("config.json", "vocab.txt", "dictionary", "bert.onnx")

        fun acousticModel(directory: File): File =
            File(directory, "model-5steps.onnx").takeIf { it.isFile } ?: File(directory, "model.onnx")

        fun isInstalled(directory: File): Boolean =
            REQUIRED.all { File(directory, it).length() > 0 } && acousticModel(directory).length() > 0
    }
}
