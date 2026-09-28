package com.ozvuchka.app.speech

import android.content.Context
import android.util.Log

/** Compact, sorted RUAccent word forms. Ambiguous words are deliberately absent. */
internal class RussianStressLexicon(private val data: ByteArray) {
    private val count: Int
    private val recordsStart: Int

    init {
        require(data.size >= 8 && data.copyOfRange(0, 4).contentEquals("RSTD".encodeToByteArray()))
        count = uint32(4)
        require(count >= 0 && count <= (data.size - 8) / 4)
        recordsStart = 8 + 4 * count
    }

    /** Returns a + before the stressed vowel, preserving the spelling of [word]. */
    fun find(word: String): String? {
        val key = word.encodeToByteArray()
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val record = recordsStart + uint32(8 + middle * 4)
            if (record < recordsStart || record + 2 > data.size) return null
            val length = data[record].toInt() and 0xff
            if (record + 2 + length > data.size) return null
            val comparison = compare(key, record + 2, length)
            when {
                comparison < 0 -> high = middle - 1
                comparison > 0 -> low = middle + 1
                else -> {
                    val stress = data[record + 1].toInt() and 0xff
                    return if (stress < word.length) word.substring(0, stress) + "+" + word.substring(stress) else null
                }
            }
        }
        return null
    }

    private fun compare(key: ByteArray, from: Int, length: Int): Int {
        for (index in 0 until minOf(key.size, length)) {
            val difference = (key[index].toInt() and 0xff) - (data[from + index].toInt() and 0xff)
            if (difference != 0) return difference
        }
        return key.size - length
    }

    private fun uint32(at: Int): Int =
        (data[at].toInt() and 0xff) or
            ((data[at + 1].toInt() and 0xff) shl 8) or
            ((data[at + 2].toInt() and 0xff) shl 16) or
            ((data[at + 3].toInt() and 0xff) shl 24)

    companion object {
        @Volatile private var cached: RussianStressLexicon? = null
        @Volatile private var unavailable = false

        fun find(context: Context, word: String): String? {
            var lexicon = cached
            if (lexicon == null && !unavailable) {
                synchronized(this) {
                    lexicon = cached
                    if (lexicon == null && !unavailable) {
                        lexicon = runCatching {
                            RussianStressLexicon(context.assets.open("ru_stress.bin").use { it.readBytes() })
                        }.onFailure { Log.e("RussianStressLexicon", "Cannot load stress dictionary", it) }
                            .getOrNull()
                        cached = lexicon
                        unavailable = lexicon == null
                    }
                }
            }
            return lexicon?.find(word)
        }
    }
}
