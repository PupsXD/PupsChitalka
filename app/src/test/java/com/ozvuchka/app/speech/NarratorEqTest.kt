package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** The narrator's presence shelf: it lifts the consonants above 6.5 kHz, but not on a narrow-band voice. */
class NarratorEqTest {
    private fun tone(frequency: Int, rate: Int, seconds: Float = 1f): FloatArray {
        val samples = FloatArray((rate * seconds).toInt()) { sin(2 * PI * frequency * it / rate).toFloat() * 0.5f }
        return samples
    }

    private fun rms(samples: FloatArray, skip: Int = 0): Double {
        var energy = 0.0
        for (index in skip until samples.size) energy += samples[index] * samples[index]
        return sqrt(energy / (samples.size - skip))
    }

    @Test
    fun aNarrowBandVoiceGetsOnlyTheHighPass() {
        // 8 kHz is below the 16 kHz where the shelf would sit at the edge of hearing: only the
        // 75 Hz high-pass runs, and a 3 kHz tone passes it unchanged.
        val rate = 8_000
        val samples = tone(3_000, rate)
        val before = rms(samples)
        NarratorEq.apply(samples, rate)
        val after = rms(samples, skip = rate / 100)
        assertTrue("полка EQ включилась ниже 16 кГц: $before → $after", abs(after - before) / before < 0.02)
    }

    @Test
    fun aFullBandVoiceGetsTheShelf() {
        // The same tone at 24 kHz keeps its level; 8 kHz is lifted by the +1.5 dB shelf, so the
        // threshold above is what the first test measures, not a missing filter.
        val rate = 24_000
        val low = tone(3_000, rate)
        val lowBefore = rms(low)
        NarratorEq.apply(low, rate)
        assertTrue(abs(rms(low, skip = rate / 100) - lowBefore) / lowBefore < 0.02)

        val high = tone(8_000, rate)
        val highBefore = rms(high)
        NarratorEq.apply(high, rate)
        val highAfter = rms(high, skip = rate / 100)
        assertTrue("полка не подняла 8 кГц: $highBefore → $highAfter", highAfter / highBefore > 1.05)
    }

    @Test
    fun silenceStaysSilent() {
        val samples = FloatArray(0)
        NarratorEq.apply(samples, 24_000)
        assertEquals(0, samples.size)
        val zeros = FloatArray(480)
        NarratorEq.apply(zeros, 24_000)
        assertTrue(zeros.all { it == 0f })
    }
}
