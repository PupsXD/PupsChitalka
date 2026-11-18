package com.ozvuchka.app.speech

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * How much Vosk's voice and timing vary: [level] is the noise scale, [durationLevel] the one for the
 * phoneme lengths. Narration stays even; a raised voice wavers more, as a real one does.
 */
internal data class VoskNoise(val level: Float, val durationLevel: Float) {
    companion object {
        private const val CALM_LEVEL = 0.55f
        private const val CALM_DURATION_LEVEL = 0.65f
        private const val EMOTIONAL_LEVEL = 0.75f
        private const val EMOTIONAL_DURATION_LEVEL = 0.85f
        private val EMOTIONAL_TONES = setOf(SpeechTone.EXCLAIM, SpeechTone.ANGRY, SpeechTone.JOY, SpeechTone.FEAR)

        /** Even narration: what every line gets while «Эмоции» are off. */
        val CALM = VoskNoise(CALM_LEVEL, CALM_DURATION_LEVEL)

        fun forLine(role: SpeechRole, tone: SpeechTone): VoskNoise =
            if (role != SpeechRole.NARRATOR && tone in EMOTIONAL_TONES) {
                VoskNoise(EMOTIONAL_LEVEL, EMOTIONAL_DURATION_LEVEL)
            } else {
                CALM
            }
    }
}

/**
 * Vosk TTS 0.10 (Alpha Cephei, Apache 2.0): 57 Russian voices from one model. Text goes through
 * [VoskFrontend], ruBERT gives each word its context, and the acoustic model (Matcha-TTS decoder plus
 * Vocos vocoder) renders 22 050 Hz audio. Not thread-safe: the caller runs one sentence at a time.
 *
 * Files in [directory]: config.json, vocab.txt, dictionary (+ dictionary.idx, built on first use),
 * bert.onnx (or bert-int8.onnx), and the acoustic model — [acousticModel] picks model-fast.onnx (the
 * decoder cut and the shapes resolved at install time, see tools/voices/vosk-prepare.py),
 * model-prepared.onnx when the decoder could not be rewritten, the 5-step file of an older install, or
 * model.onnx as published. A prepared graph also gets ONNX Runtime's own optimized copy (`….ort`),
 * kept only while the model file it was built from is unchanged.
 */
internal class VoskTts(
    directory: File,
    private val threads: Int,
    /** Stress for a word the model's dictionary lacks; null when the lexicon does not know it either. */
    private val stressFallback: (String) -> String? = { null },
) : AutoCloseable {
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
        frontend = VoskFrontend(ids, tokenizer, dictionary::find, stressFallback)
        bert = env.createSession(bertModel(directory).path, options())
        acoustic = createAcoustic(acousticModel(directory))
    }

    /**
     * The acoustic model, from ONNX Runtime's optimized copy when there is one. The copy loads in a
     * fraction of the time, but only a graph that had its shapes resolved ahead of time gains anything
     * from it (57 s → 2.6 s; the copy then takes 1.2 s), so it is written for prepared graphs only.
     * The copy is tied to the model file by [OrtCache]: a replaced model or a copy without a stamp is
     * not loaded but rebuilt. Anything unexpected about the copy falls back to the .onnx.
     */
    private fun createAcoustic(model: File): OrtSession {
        val cache = File(model.path + ".ort")
        val stamp = File(model.path + ".ort.stamp")
        if (OrtCache.isValid(model, cache, stamp)) {
            try {
                return env.createSession(cache.path, options())
            } catch (error: Exception) {
                android.util.Log.w("VoskTts", "optimized graph is not usable, loading the model", error)
                OrtCache.forget(cache, stamp)
            }
        } else if (cache.exists() || stamp.exists()) {
            // Built from another model or before stamps existed: it cannot be trusted.
            OrtCache.forget(cache, stamp)
        }
        if (isPrepared(model)) {
            try {
                return env.createSession(model.path, options(cache)).also { OrtCache.remember(model, stamp) }
            } catch (error: Exception) {
                android.util.Log.w("VoskTts", "optimized graph not written, loading the model again", error)
                cache.delete()
            }
        }
        return env.createSession(model.path, options())
    }

    private fun options(cache: File? = null): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        if (cache != null) {
            // Saving needs the same options at load time; when the runtime cannot, the model is still usable.
            try {
                setOptimizedModelFilePath(cache.path)
            } catch (error: Exception) {
                android.util.Log.w("VoskTts", "optimized graph cannot be saved", error)
            }
        }
    }

    /**
     * Audio for [text]; [speed] above 1 speaks faster (the model shortens the sounds, pitch unchanged).
     * [noise] and [durationNoise] vary the voice and timing from run to run; 0 makes the output exact.
     * See [VoskNoise] for how a line's role and tone choose them.
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

        /** The acoustic model to load, best first; a fresh install has model-fast.onnx, an old one model-5steps.onnx. */
        fun acousticModel(directory: File): File =
            MODELS.map { File(directory, it) }.firstOrNull { it.isFile } ?: File(directory, MODELS.last())

        private val MODELS = listOf(
            "model-fast.onnx", "model-prepared.onnx", "model-5steps.onnx", "model.onnx",
        )

        /** Whether [model] is a graph whose shapes were resolved at install time, the only kind the .ort copy helps. */
        fun isPrepared(model: File): Boolean = model.name == "model-fast.onnx" || model.name == "model-prepared.onnx"

        /** ruBERT, quantized to INT8 when that file was put next to it (halves the memory it needs, see the PUP-003 report). */
        fun bertModel(directory: File): File =
            File(directory, "bert-int8.onnx").takeIf { it.isFile } ?: File(directory, "bert.onnx")

        fun isInstalled(directory: File): Boolean =
            REQUIRED.all { File(directory, it).length() > 0 } && acousticModel(directory).length() > 0
    }
}
