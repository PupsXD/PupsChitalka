package com.ozvuchka.app.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A spoken word: the audio frame where it starts and its [start, end) range in a text. Engines
 * report ranges in the synthesized text; the player turns them into paragraph offsets.
 */
data class WordMark(val frame: Int, val start: Int, val end: Int)

/**
 * Mono float audio as produced by every synthesizer, with word starts if the engine reports them.
 * [speedUp] above 1 asks to play it that much faster than [sampleRate], which raises its pitch. Such a
 * shift drags the formants along with it, so neural voices no longer ask for one: they take the pace alone.
 */
class SynthesizedAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val words: List<WordMark> = emptyList(),
    val speedUp: Float = 1f,
)

/**
 * Band-limited resampler (Kaiser-windowed sinc). Voices come at 22.05, 24, 44.1 or 48 kHz and
 * are played through one continuous AudioTrack, so a switch between engines must not change pitch
 * or add aliasing.
 */
internal object Resampler {
    private const val ZERO_CROSSINGS = 16
    private const val TABLE_RESOLUTION = 256
    private const val KAISER_BETA = 8.6

    private val table: FloatArray by lazy {
        val size = ZERO_CROSSINGS * TABLE_RESOLUTION + 1
        val denominator = besselI0(KAISER_BETA)
        FloatArray(size) { index ->
            val x = index.toDouble() / TABLE_RESOLUTION
            val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
            val ratio = x / ZERO_CROSSINGS
            val window = besselI0(KAISER_BETA * sqrt(max(0.0, 1.0 - ratio * ratio))) / denominator
            (sinc * window).toFloat()
        }
    }

    fun resample(input: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        if (fromRate == toRate || input.isEmpty()) return input
        require(fromRate > 0 && toRate > 0)
        val step = fromRate.toDouble() / toRate
        // Downsampling lowers the cutoff to the new Nyquist frequency.
        val cutoff = min(1.0, toRate.toDouble() / fromRate) * 0.97
        val outputLength = floor(input.size / step).toInt().coerceAtLeast(1)
        val output = FloatArray(outputLength)
        val radius = ZERO_CROSSINGS / cutoff
        val kernel = table
        for (n in 0 until outputLength) {
            val position = n * step
            val first = max(0, (position - radius).toInt() + 1)
            val last = min(input.size - 1, (position + radius).toInt())
            var sum = 0.0
            var index = first
            while (index <= last) {
                val distance = abs(position - index) * cutoff * TABLE_RESOLUTION
                val tableIndex = distance.toInt()
                if (tableIndex < kernel.size - 1) {
                    val fraction = (distance - tableIndex).toFloat()
                    val weight = kernel[tableIndex] + (kernel[tableIndex + 1] - kernel[tableIndex]) * fraction
                    sum += input[index] * weight
                }
                index++
            }
            output[n] = (sum * cutoff).toFloat()
        }
        return output
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2
        var k = 1
        while (k < 50) {
            term *= (half / k) * (half / k)
            sum += term
            if (term < 1e-12 * sum) break
            k++
        }
        return sum
    }
}

internal object AudioShaping {
    /**
     * Removes leading and trailing silence, keeping a short margin so soft consonants survive.
     * The player adds its own, consistent pauses between sentences.
     */
    fun trimSilence(samples: FloatArray, sampleRate: Int, threshold: Float = 0.004f, marginMs: Int = 35): FloatArray {
        val bounds = speechBounds(samples, sampleRate, threshold, marginMs)
        if (bounds.isEmpty()) return FloatArray(0)
        if (bounds.first == 0 && bounds.last == samples.lastIndex) return samples
        return samples.copyOfRange(bounds.first, bounds.last + 1)
    }

    /** The range [trimSilence] keeps; empty when there is no sound at all. */
    fun speechBounds(samples: FloatArray, sampleRate: Int, threshold: Float = 0.004f, marginMs: Int = 35): IntRange {
        if (samples.isEmpty()) return IntRange.EMPTY
        val window = (sampleRate / 200).coerceAtLeast(1) // 5 ms
        fun loud(from: Int): Boolean {
            val end = min(samples.size, from + window)
            var peak = 0f
            for (i in from until end) peak = max(peak, abs(samples[i]))
            return peak > threshold
        }
        var start = 0
        while (start < samples.size && !loud(start)) start += window
        if (start >= samples.size) return IntRange.EMPTY
        var end = samples.size
        while (end > start && !loud(max(start, end - window))) end -= window
        val margin = sampleRate * marginMs / 1000
        return max(0, start - margin) until min(samples.size, end + margin)
    }

    /** RMS over 20 ms frames that are clearly voiced, so pauses do not dilute the estimate. */
    fun speechRms(samples: FloatArray, sampleRate: Int): Float {
        val frame = (sampleRate / 50).coerceAtLeast(1)
        var energy = 0.0
        var count = 0L
        var start = 0
        while (start < samples.size) {
            val end = min(samples.size, start + frame)
            var frameEnergy = 0.0
            for (i in start until end) frameEnergy += samples[i] * samples[i]
            val frameRms = sqrt(frameEnergy / (end - start))
            if (frameRms > 0.01) {
                energy += frameEnergy
                count += end - start
            }
            start = end
        }
        return if (count == 0L) 0f else sqrt(energy / count).toFloat()
    }

    /** Applies gain with a soft knee above -1 dBFS instead of hard clipping. */
    fun applyGain(samples: FloatArray, gain: Float) {
        for (i in samples.indices) {
            val x = samples[i] * gain
            val magnitude = abs(x)
            samples[i] = if (magnitude <= 0.89f) {
                x
            } else {
                val limited = 0.89f + 0.1f * tanh((magnitude - 0.89f) / 0.1f).toFloat()
                if (x < 0) -limited else limited
            }
        }
    }

    /** Short fades prevent clicks where trimmed audio meets silence or another voice. */
    fun fadeEdges(samples: FloatArray, sampleRate: Int, fadeMs: Int = 6) {
        val length = min(samples.size / 2, sampleRate * fadeMs / 1000)
        for (i in 0 until length) {
            val gain = i.toFloat() / length
            samples[i] *= gain
            samples[samples.size - 1 - i] *= gain
        }
    }
}

/**
 * The narrator's presence: a high-pass that takes the rumble of the room out of the voice and a small
 * shelf that lifts the consonants, so a voice from a small speaker sounds warm rather than muffled.
 * Two RBJ biquads, applied before the loudness gain, so the soft limiter still sees the final level.
 */
internal object NarratorEq {
    private const val RUMBLE_HZ = 75.0
    private const val PRESENCE_HZ = 6_500.0
    private const val PRESENCE_DB = 1.5
    /** Butterworth: the flattest passband, no peak at the corner. */
    private const val Q = 0.7071
    /** Below this rate the presence shelf would sit at the edge of hearing, so only the high-pass runs. */
    private const val PRESENCE_MIN_RATE = 16_000

    fun apply(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty() || sampleRate <= 0) return
        highPass(sampleRate).apply(samples)
        if (sampleRate >= PRESENCE_MIN_RATE) presence(min(PRESENCE_HZ, sampleRate * 0.45), sampleRate).apply(samples)
    }

    private fun highPass(sampleRate: Int): Biquad {
        val w0 = 2 * PI * RUMBLE_HZ / sampleRate
        val cosine = cos(w0)
        val alpha = sin(w0) / (2 * Q)
        val a0 = 1 + alpha
        return Biquad(
            b0 = (1 + cosine) / 2 / a0,
            b1 = -(1 + cosine) / a0,
            b2 = (1 + cosine) / 2 / a0,
            a1 = -2 * cosine / a0,
            a2 = (1 - alpha) / a0,
        )
    }

    /** A high shelf: flat below [hz], [PRESENCE_DB] louder above it. */
    private fun presence(hz: Double, sampleRate: Int): Biquad {
        val w0 = 2 * PI * hz / sampleRate
        val cosine = cos(w0)
        val amplitude = 10.0.pow(PRESENCE_DB / 40)
        val alpha = sin(w0) / 2 * sqrt(2.0)
        val edge = 2 * sqrt(amplitude) * alpha
        val a0 = (amplitude + 1) - (amplitude - 1) * cosine + edge
        return Biquad(
            b0 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosine + edge) / a0,
            b1 = -2 * amplitude * ((amplitude - 1) + (amplitude + 1) * cosine) / a0,
            b2 = amplitude * ((amplitude + 1) + (amplitude - 1) * cosine - edge) / a0,
            a1 = 2 * ((amplitude - 1) - (amplitude + 1) * cosine) / a0,
            a2 = ((amplitude + 1) - (amplitude - 1) * cosine - edge) / a0,
        )
    }

    /** One section in transposed direct form II; coefficients are normalised by a0. */
    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun apply(samples: FloatArray) {
            for (index in samples.indices) {
                val x = samples[index].toDouble()
                val y = b0 * x + z1
                z1 = b1 * x - a1 * y + z2
                z2 = b2 * x - a2 * y
                samples[index] = y.toFloat()
            }
        }
    }
}

/**
 * Keeps each voice at a similar loudness. The gain follows a slow average per voice, so
 * a whisper stays quieter than a shout, but a quiet engine is not quieter than a loud one.
 */
internal class LoudnessMatcher(private val targetRms: Float = 0.085f) {
    private val averages = HashMap<String, Float>()

    @Synchronized
    fun gainFor(voiceKey: String, segmentRms: Float): Float {
        if (segmentRms <= 0f) return 1f
        val previous = averages[voiceKey]
        val average = if (previous == null) segmentRms else previous * 0.8f + segmentRms * 0.2f
        averages[voiceKey] = average
        return (targetRms / average).coerceIn(0.5f, 3.0f)
    }
}

/** Reads the WAV files written by Android's TextToSpeech.synthesizeToFile into mono floats. */
internal object WavReader {
    fun read(file: File): SynthesizedAudio {
        RandomAccessFile(file, "r").use { input ->
            val header = ByteArray(12)
            input.readFully(header)
            require(String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WAVE") {
                "Движок вернул не WAV"
            }
            var channels = 1
            var sampleRate = 0
            var bits = 16
            var format = 1
            while (input.filePointer + 8 <= input.length()) {
                val chunkHeader = ByteArray(8)
                input.readFully(chunkHeader)
                val id = String(chunkHeader, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
                when (id) {
                    "fmt " -> {
                        val fmt = ByteArray(size.toInt().coerceAtLeast(16))
                        input.readFully(fmt, 0, size.toInt())
                        val buffer = ByteBuffer.wrap(fmt).order(ByteOrder.LITTLE_ENDIAN)
                        format = buffer.getShort(0).toInt() and 0xFFFF
                        channels = (buffer.getShort(2).toInt() and 0xFFFF).coerceAtLeast(1)
                        sampleRate = buffer.getInt(4)
                        bits = buffer.getShort(14).toInt() and 0xFFFF
                        if (format == 0xFFFE && size >= 26) format = buffer.getShort(24).toInt() and 0xFFFF
                    }
                    "data" -> {
                        require(sampleRate > 0) { "В WAV нет формата" }
                        // Some engines leave the data size at 0 or 0xFFFFFFFF while streaming.
                        val available = input.length() - input.filePointer
                        val length = if (size == 0L || size > available) available else size
                        val data = ByteArray(length.toInt())
                        input.readFully(data)
                        return SynthesizedAudio(decode(data, format, bits, channels), sampleRate)
                    }
                    else -> input.seek(input.filePointer + size + (size and 1))
                }
            }
        }
        throw IllegalStateException("В WAV нет звука")
    }

    private fun decode(data: ByteArray, format: Int, bits: Int, channels: Int): FloatArray {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val bytesPerSample = bits / 8
        require(bytesPerSample in 1..4) { "Неподдерживаемый формат звука" }
        val frames = data.size / (bytesPerSample * channels)
        val output = FloatArray(frames)
        for (frame in 0 until frames) {
            var sum = 0f
            for (channel in 0 until channels) {
                val offset = (frame * channels + channel) * bytesPerSample
                sum += when {
                    format == 3 && bits == 32 -> buffer.getFloat(offset)
                    bits == 8 -> ((data[offset].toInt() and 0xFF) - 128) / 128f
                    bits == 16 -> buffer.getShort(offset) / 32768f
                    bits == 24 -> {
                        val value = (data[offset].toInt() and 0xFF) or
                            ((data[offset + 1].toInt() and 0xFF) shl 8) or
                            (data[offset + 2].toInt() shl 16)
                        value / 8_388_608f
                    }
                    else -> buffer.getInt(offset) / 2_147_483_648f
                }
            }
            output[frame] = sum / channels
        }
        return output
    }
}
