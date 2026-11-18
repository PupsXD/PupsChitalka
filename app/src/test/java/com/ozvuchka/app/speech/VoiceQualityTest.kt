package com.ozvuchka.app.speech

import com.ozvuchka.app.speech.SpeechTone.ANGRY
import com.ozvuchka.app.speech.SpeechTone.EXCLAIM
import com.ozvuchka.app.speech.SpeechTone.FEAR
import com.ozvuchka.app.speech.SpeechTone.JOY
import com.ozvuchka.app.speech.SpeechTone.NEUTRAL
import com.ozvuchka.app.speech.SpeechTone.UNSURE
import com.ozvuchka.app.speech.SpeechTone.WHISPER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

/** What a line sounds like: its tone without the pitch trick, Vosk's breath, and the narrator's EQ. */
class VoiceQualityTest {
    @Test
    fun aNeuralVoiceSaysAToneWithoutMovingItsFormants() {
        // Slowing the model and playing it faster would raise the pitch, and the formants with it.
        for (tone in SpeechTone.entries) {
            for (strength in listOf(0f, 0.5f, 1f)) {
                val prosody = tone.prosody(strength)
                val pace = VoicePace.of(1.2f, prosody)
                val label = "$tone at $strength"
                assertEquals(label, VoicePace.NATURAL_PITCH, pace.speedUp, 0f)
                assertEquals(label, 1.2f * prosody.rate, pace.speed, 1e-6f)
                assertTrue(label, pace.speed > 0f)
            }
        }
    }

    @Test
    fun aToneThatOnlyRaisedItsPitchKeepsItsPaceAndItsLoudness() {
        // «Что?!» used to be rendered 9% slower and played 9% faster: an audible chipmunk.
        val surprise = VoicePace.of(1f, SpeechTone.SURPRISE.prosody(1f))
        assertEquals(1f, surprise.speed, 1e-6f)
        assertEquals(1f, surprise.speedUp, 0f)
        assertTrue(SpeechTone.SURPRISE.prosody(1f).gain > 1f)

        // The pace still carries the tones that lean on it, and the gain still carries the loudness.
        assertEquals(1.1f, VoicePace.of(1f, ANGRY.prosody(1f)).speed, 1e-5f)
        assertEquals(0.92f, VoicePace.of(1f, WHISPER.prosody(1f)).speed, 1e-5f)
        assertTrue(ANGRY.prosody(1f).gain > 1f)
        assertTrue(WHISPER.prosody(1f).gain < 1f)
        assertEquals(Prosody.NEUTRAL, NEUTRAL.prosody(1f))
    }

    @Test
    fun voskBreathesLessInNarrationAndMoreInARaisedVoice() {
        val calm = VoskNoise.forLine(SpeechRole.NARRATOR, NEUTRAL)
        assertEquals(0.55f, calm.level, 0f)
        assertEquals(0.65f, calm.durationLevel, 0f)
        for (tone in listOf(EXCLAIM, ANGRY, JOY, FEAR)) {
            val raised = VoskNoise.forLine(SpeechRole.MALE, tone)
            assertEquals("$tone", 0.75f, raised.level, 0f)
            assertEquals("$tone", 0.85f, raised.durationLevel, 0f)
            assertTrue("$tone wavers more than narration", raised.level > calm.level && raised.durationLevel > calm.durationLevel)
        }
        // A calm line keeps the narrator's breath even when a character says it.
        assertEquals(calm, VoskNoise.forLine(SpeechRole.FEMALE, WHISPER))
        assertEquals(calm, VoskNoise.forLine(SpeechRole.FEMALE, UNSURE))
        // The narrator never takes an emotional line's breath, even if one leaks into the segment.
        assertEquals(calm, VoskNoise.forLine(SpeechRole.NARRATOR, ANGRY))
    }

    @Test
    fun theEqKeepsTheLoudnessAndThePresence() {
        for (rate in listOf(22_050, 24_000)) {
            val voice = tone(200.0, 0.5f, rate)
            val before = rms(voice)
            NarratorEq.apply(voice, rate)
            assertTrue("$rate stays finite and unclipped", voice.all { it.isFinite() && abs(it) <= 1f })
            assertEquals("$rate keeps the loudness", before, rms(voice), before * 0.05f)

            // The presence shelf lifts the consonants: 8 kHz comes out about 1.5 dB louder.
            val consonants = tone(8_000.0, 0.1f, rate)
            val quiet = rms(consonants)
            NarratorEq.apply(consonants, rate)
            val lift = rms(consonants) / quiet
            assertTrue("$rate lifts the presence by 1.5 dB, got $lift", lift in 1.10f..1.25f)
        }
    }

    @Test
    fun theEqIsStableAtEveryOutputRate() {
        // 8 kHz comes from some system engines; 22.05 and 24 kHz from Vosk, Kokoro and RuVoice.
        for (rate in listOf(8_000, 22_050, 24_000)) {
            val impulse = FloatArray(rate) { if (it == 0) 1f else 0f }
            NarratorEq.apply(impulse, rate)
            assertTrue("$rate stays finite", impulse.all { it.isFinite() && abs(it) < 4f })
            // 100 ms later the ring has died out: every pole is inside the unit circle.
            assertTrue("$rate decays", impulse.copyOfRange(rate / 10, rate).all { abs(it) < 1e-5f })
        }
    }

    private fun tone(hz: Double, amplitude: Float, rate: Int): FloatArray =
        FloatArray(rate) { sin(2 * PI * hz * it / rate).toFloat() * amplitude }

    /** RMS of the middle half, away from the filter's own start-up. */
    private fun rms(samples: FloatArray): Float {
        val from = samples.size / 4
        val to = samples.size * 3 / 4
        var energy = 0.0
        for (index in from until to) energy += samples[index] * samples[index]
        return sqrt(energy / (to - from).coerceAtLeast(1)).toFloat()
    }
}
