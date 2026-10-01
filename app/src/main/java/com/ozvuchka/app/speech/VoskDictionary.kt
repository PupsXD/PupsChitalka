package com.ozvuchka.app.speech

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Vosk's pronunciation dictionary: 2 million lines `word probability phonemes`, sorted by the bytes
 * of the word, a word's variants on adjacent lines. Like vosk-tts, a word reads with its most
 * probable variant (the first one on a tie). The 101 MB text stays as it is and is memory-mapped; a
 * side index holds the line offset of each word's chosen variant (8 MB), searched by binary search.
 */
internal class VoskDictionary private constructor(private val text: ByteBuffer, private val offsets: ByteBuffer) {
    private val count = offsets.capacity() / 4 - 2

    init {
        require(offsets.getInt(0) == MAGIC && offsets.getInt(4) == count) { "bad dictionary index" }
    }

    /** The phonemes of [word] (lowercase, as in the dictionary), or null when the word is absent. */
    fun find(word: String): String? {
        val key = word.encodeToByteArray()
        var low = 0
        var high = count - 1
        while (low <= high) {
            val middle = (low + high) ushr 1
            val line = offsets.getInt(8 + 4 * middle)
            val comparison = compare(key, line)
            when {
                comparison < 0 -> high = middle - 1
                comparison > 0 -> low = middle + 1
                else -> return phonemes(line)
            }
        }
        return null
    }

    /** [key] against the word that starts the line at [line]: negative when the key sorts first. */
    private fun compare(key: ByteArray, line: Int): Int {
        var i = 0
        while (true) {
            val stored = text.get(line + i).toInt() and 0xff
            val wordEnded = stored == SPACE
            if (i == key.size) return if (wordEnded) 0 else -1
            if (wordEnded) return 1
            val difference = (key[i].toInt() and 0xff) - stored
            if (difference != 0) return difference
            i++
        }
    }

    /** `word prob a0 b c` → `a0 b c`. */
    private fun phonemes(line: Int): String {
        var at = line
        repeat(2) { while (text.get(at).toInt() != SPACE) at++; at++ }
        val start = at
        while (at < text.limit() && text.get(at).toInt() != NEWLINE) at++
        val bytes = ByteArray(at - start)
        for (i in bytes.indices) bytes[i] = text.get(start + i)
        return bytes.decodeToString().trimEnd('\r')
    }

    companion object {
        private const val MAGIC = 0x58444b56 // "VKDX"
        private const val SPACE = ' '.code
        private const val NEWLINE = '\n'.code

        fun open(dictionary: File, index: File): VoskDictionary {
            val text = RandomAccessFile(dictionary, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            val offsets = RandomAccessFile(index, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()) }
            return VoskDictionary(text, offsets.order(ByteOrder.LITTLE_ENDIAN))
        }

        /**
         * Writes [index] for [dictionary] in one pass: for each word the offset of its most probable
         * line. Fails on a file that is not sorted, since the search would then miss words.
         */
        fun buildIndex(dictionary: File, index: File) {
            require(dictionary.length() < Int.MAX_VALUE) { "dictionary too large" }
            var offsets = IntArray(1 shl 21)
            var count = 0
            var previous = ByteArray(0)
            var bestProbability = 0.0
            BufferedInputStream(FileInputStream(dictionary), 1 shl 16).use { input ->
                val line = java.io.ByteArrayOutputStream(256)
                var position = 0L
                var lineStart = 0L
                fun take() {
                    val bytes = line.toByteArray()
                    line.reset()
                    val firstSpace = bytes.indexOf(SPACE.toByte())
                    if (firstSpace <= 0) return
                    val word = bytes.copyOfRange(0, firstSpace)
                    val secondSpace = (firstSpace + 1 until bytes.size).firstOrNull { bytes[it] == SPACE.toByte() } ?: return
                    val probability = String(bytes, firstSpace + 1, secondSpace - firstSpace - 1, Charsets.US_ASCII).toDoubleOrNull() ?: return
                    val order = compareBytes(word, previous)
                    if (order == 0) {
                        // vosk-tts keeps a later variant only when it is strictly more probable
                        if (probability > bestProbability) {
                            offsets[count - 1] = lineStart.toInt()
                            bestProbability = probability
                        }
                        return
                    }
                    require(order > 0) { "dictionary is not sorted at «${word.decodeToString()}»" }
                    if (probability <= 0.0) return // vosk-tts never keeps a word whose probability is not above 0
                    if (count == offsets.size) offsets = offsets.copyOf(offsets.size * 2)
                    offsets[count++] = lineStart.toInt()
                    previous = word
                    bestProbability = probability
                }
                while (true) {
                    val b = input.read()
                    if (b < 0) break
                    position++
                    if (b == NEWLINE) {
                        take()
                        lineStart = position
                    } else {
                        line.write(b)
                    }
                }
                if (line.size() > 0) take()
            }
            val partial = File(index.path + ".part")
            partial.outputStream().buffered(1 shl 16).use { out ->
                val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(MAGIC).putInt(count)
                out.write(header.array())
                val chunk = ByteBuffer.allocate(4 * 4096).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until count) {
                    chunk.putInt(offsets[i])
                    if (!chunk.hasRemaining()) {
                        out.write(chunk.array(), 0, chunk.position())
                        chunk.clear()
                    }
                }
                out.write(chunk.array(), 0, chunk.position())
            }
            if (!partial.renameTo(index)) {
                index.delete()
                check(partial.renameTo(index)) { "cannot write ${index.name}" }
            }
        }

        private fun compareBytes(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val difference = (a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)
                if (difference != 0) return difference
            }
            return a.size - b.size
        }
    }
}
