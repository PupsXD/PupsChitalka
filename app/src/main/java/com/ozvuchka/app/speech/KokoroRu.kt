package com.ozvuchka.app.speech

import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * Kokoro RU (zaakirio/kokoro-ru): a Russian fine-tune of Kokoro-82M, 24 kHz, three voices —
 * sveta and masha from one checkpoint, dima from another. The model is language-blind: it takes
 * phoneme ids, so Russian words are phonemized by espeak-ng inside sherpa-onnx, and the file set
 * here is exactly what makes that work on a phone.
 *
 * Two things the upstream ONNX export lacks, and this object adds on install:
 * - the metadata sherpa-onnx reads (sample rate, speaker count, that espeak-ng phonemizes);
 * - a `tokens.txt` built from the model's own vocabulary, with the four symbols espeak-ng emits for
 *   Russian but Kokoro never trained on folded onto the nearest ones (the same folding as ru_g2p.py).
 *
 * Download size is the reason for shipping the FP32 checkpoint: measured on a desktop CPU, the FP32
 * model synthesizes at RTF 0.29–0.35 against 0.81–0.88 for the q8 export — its quantized matrix
 * multiplies are slower than plain ones on ARM-class cores too, as the English Kokoro measurement
 * showed (docs/VOICE_CANDIDATES.md).
 */
internal object KokoroRu {
    const val BASE_MODEL = "onnx/model.onnx"
    const val DIMA_MODEL = "onnx/model_dima.onnx"
    const val VOICES = "voices.bin"
    const val TOKENS = "tokens.txt"
    const val CONFIG = "config.json"
    const val ESPEAK_DIR = "espeak-data"

    const val SVETA = 0
    const val MASHA = 1
    const val DIMA = 2
    const val SPEAKERS = 3
    const val SAMPLE_RATE = 24_000

    /** Voice packs of the model: one style vector per phoneme count, 510 rows of 256 floats. */
    const val STYLE_ROWS = 510
    const val STYLE_DIM = 256

    /** The espeak-ng data Russian needs; the upstream tree carries a dictionary for every language. */
    val espeakFiles: List<String> = listOf(
        "phontab", "phondata", "phonindex", "intonations", "ru_dict", "en_dict",
        "lang/zle/ru", "lang/gmw/en",
    )

    /** Voice packs of [model]'s checkpoint, in the order sherpa-onnx numbers its speakers. */
    fun packs(model: SpeechModel): List<String> = when (model) {
        SpeechModel.KOKORO_RU_DIMA -> listOf("voices/dima.bin")
        else -> listOf("voices/sveta.bin", "voices/masha.bin")
    }

    /** The download a picker voice needs. */
    fun speechModelFor(speaker: Int): SpeechModel =
        if (speaker == DIMA) SpeechModel.KOKORO_RU_DIMA else SpeechModel.KOKORO_RU

    fun modelFile(model: SpeechModel): String = if (model == SpeechModel.KOKORO_RU_DIMA) DIMA_MODEL else BASE_MODEL

    /** [speaker] in the numbering of [model]'s own voices.bin. */
    fun localSpeaker(model: SpeechModel, speaker: Int): Int =
        if (model == SpeechModel.KOKORO_RU_DIMA) 0 else speaker.coerceIn(SVETA, MASHA)

    fun isMale(speaker: Int): Boolean = speaker == DIMA

    /**
     * Turns the downloaded files into what sherpa-onnx loads: `voices.bin` (the style packs one after
     * another), `tokens.txt` (the vocabulary) and the model metadata.
     */
    fun prepare(directory: File, model: SpeechModel) {
        val files = packs(model).map { File(directory, it) }
        val voices = File(directory, VOICES)
        voices.outputStream().buffered().use { sink ->
            files.forEach { pack -> pack.inputStream().use { it.copyTo(sink) } }
        }
        val speakers = (voices.length() / (STYLE_ROWS.toLong() * STYLE_DIM * 4)).toInt().coerceAtLeast(1)
        File(directory, TOKENS).writeText(KokoroRuTokens.build(File(directory, CONFIG).readText()))
        KokoroOnnxMetadata.ensure(
            File(directory, modelFile(model)),
            linkedMapOf(
                "sample_rate" to SAMPLE_RATE.toString(),
                "version" to "2",
                "n_speakers" to speakers.toString(),
                "has_espeak" to "1",
                "voice" to "ru",
                "style_dim" to "$STYLE_ROWS,1,$STYLE_DIM",
                "model_type" to "kokoro",
                "language" to "Russian",
            ),
        )
    }
}

/**
 * `tokens.txt` for the Russian Kokoro: one line «symbol id» per phoneme, as sherpa-onnx reads it.
 * Every token must be a single code point. espeak-ng's Russian rules emit four symbols Kokoro never
 * saw — ɭ (hard л), ɵ (stressed ё), ʐ and ʑ (assimilated ж and щ) — and ru_g2p.py folds them onto
 * l, o and ʒ before feeding the model; the same folding is done here by giving them those ids.
 */
internal object KokoroRuTokens {
    private val ALIASES = mapOf("ɭ" to "l", "ɵ" to "o", "ʐ" to "ʒ", "ʑ" to "ʒ")

    fun build(configJson: String): String {
        val vocab = JSONObject(configJson).getJSONObject("vocab")
        val symbols = HashMap<String, Int>()
        for (symbol in vocab.keys()) {
            if (symbol.codePointCount(0, symbol.length) != 1) continue
            symbols[symbol] = vocab.getInt(symbol)
        }
        val lines = symbols.entries.sortedBy { it.value }.map { (symbol, id) -> "$symbol $id" }.toMutableList()
        for ((alias, target) in ALIASES) {
            if (alias in symbols) continue
            val id = symbols[target] ?: continue
            lines += "$alias $id"
        }
        return lines.joinToString("\n", postfix = "\n")
    }
}

/**
 * The metadata sherpa-onnx reads from a Kokoro ONNX file. ONNX is protobuf, and metadata entries can
 * sit anywhere in the top-level message, so a missing block is appended to the end of the file
 * instead of rewriting a 300 MB download. Keys the file already states are left alone.
 */
internal object KokoroOnnxMetadata {
    /** ModelProto.metadata_props = 14; StringStringEntryProto{string key = 1; string value = 2;}. */
    private const val METADATA_FIELD = 14

    fun ensure(file: File, wanted: Map<String, String>): Boolean {
        val missing = wanted.filterKeys { it !in read(file) }
        if (missing.isEmpty()) return false
        file.appendBytes(encode(missing))
        return true
    }

    fun read(file: File): Map<String, String> {
        val found = HashMap<String, String>()
        BufferedInputStream(FileInputStream(file), 1 shl 16).use { input ->
            while (true) {
                val tag = input.readVarint() ?: break
                when ((tag and 7).toInt()) {
                    0 -> input.readVarint() ?: break
                    1 -> if (!input.skipFully(8)) break
                    2 -> {
                        val length = input.readVarint() ?: break
                        if (tag ushr 3 == METADATA_FIELD.toLong()) {
                            val entry = ByteArray(length.toInt())
                            input.readFully(entry)
                            parseEntry(entry)?.let { (key, value) -> found[key] = value }
                        } else if (!input.skipFully(length)) {
                            break
                        }
                    }
                    5 -> if (!input.skipFully(4)) break
                    else -> break // groups do not occur in ONNX files
                }
            }
        }
        return found
    }

    private fun encode(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((key, value) in entries) {
            val entry = ByteArrayOutputStream()
            entry.writeString(1, key)
            entry.writeString(2, value)
            out.writeVarint((METADATA_FIELD shl 3 or 2).toLong())
            out.writeVarint(entry.size().toLong())
            entry.writeTo(out)
        }
        return out.toByteArray()
    }

    private fun parseEntry(bytes: ByteArray): Pair<String, String>? {
        var key: String? = null
        var value: String? = null
        var position = 0
        while (position < bytes.size) {
            val tag = readVarint(bytes, position) ?: return null
            position = tag.second
            val length = readVarint(bytes, position) ?: return null
            position = length.second
            val end = position + length.first.toInt()
            if (end > bytes.size) return null
            when ((tag.first ushr 3).toInt()) {
                1 -> key = String(bytes, position, length.first.toInt(), Charsets.UTF_8)
                2 -> value = String(bytes, position, length.first.toInt(), Charsets.UTF_8)
            }
            position = end
        }
        val parsedKey = key ?: return null
        return parsedKey to (value ?: return null)
    }

    private fun readVarint(bytes: ByteArray, from: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var position = from
        while (position < bytes.size && shift < 64) {
            val byte = bytes[position++].toInt() and 0xff
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result to position
            shift += 7
        }
        return null
    }

    private fun InputStream.readVarint(): Long? {
        var result = 0L
        var shift = 0
        while (shift < 64) {
            val byte = read()
            if (byte < 0) return null
            result = result or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
        return null
    }

    private fun InputStream.skipFully(count: Long): Boolean {
        var left = count
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) return false
            left -= read
        }
        return true
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            check(read > 0) { "файл модели оборвался" }
            offset += read
        }
    }

    private fun ByteArrayOutputStream.writeVarint(value: Long) {
        var rest = value
        while (true) {
            val byte = (rest and 0x7f).toInt()
            rest = rest ushr 7
            if (rest == 0L) {
                write(byte)
                return
            }
            write(byte or 0x80)
        }
    }

    private fun ByteArrayOutputStream.writeString(field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint((field shl 3 or 2).toLong())
        writeVarint(bytes.size.toLong())
        write(bytes)
    }
}
