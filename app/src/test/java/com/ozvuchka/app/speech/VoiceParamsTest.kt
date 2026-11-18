package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each engine is asked for a line. The decision used to live inside [SynthesisHub.synthesize],
 * where no test could reach it: the pace, the player's speed-up, the noise Vosk varies itself by and
 * the pitch a system engine applies on its own.
 */
class VoiceParamsTest {
    private val neutral = Prosody.NEUTRAL
    private val whisper = SpeechTone.WHISPER.prosody(1f)
    private val surprise = SpeechTone.SURPRISE.prosody(1f)

    private fun params(
        engine: VoiceEngine,
        prosody: Prosody = neutral,
        emotions: EmotionLevel = EmotionLevel.OFF,
        role: SpeechRole = SpeechRole.NARRATOR,
        tone: SpeechTone = SpeechTone.NEUTRAL,
    ) = voiceParams(engine, 1f, prosody, emotions, role, tone)

    @Test
    fun everyEngineGetsAPaceItCanUse() {
        assertEquals(1.05f, params(VoiceEngine.SUPERTONIC).speed, 0.0001f)
        assertEquals(1f, params(VoiceEngine.KOKORO).speed, 0.0001f)
        assertEquals(1f, params(VoiceEngine.KOKORO_RU).speed, 0.0001f)
        assertEquals(1f, params(VoiceEngine.VOSK).speed, 0.0001f)
        assertEquals(1f, params(VoiceEngine.SYSTEM).speed, 0.0001f)
        // The tone's pace reaches every engine; Supertone's own 1.05 stays on top of it.
        assertEquals(1.05f * whisper.rate, params(VoiceEngine.SUPERTONIC, whisper).speed, 0.0001f)
        assertEquals(whisper.rate, params(VoiceEngine.SYSTEM, whisper).speed, 0.0001f)
        // Neural voices never ask the player for a speed-up: it would raise the pitch with the formants.
        VoiceEngine.entries.forEach { assertEquals(1f, params(it).speedUp, 0f) }
    }

    @Test
    fun onlyTheSystemEngineTakesThePitch() {
        assertEquals(surprise.pitch, params(VoiceEngine.SYSTEM, surprise).pitch, 0.0001f)
        assertTrue(params(VoiceEngine.SYSTEM, surprise).pitch > 1f)
        assertEquals(1f, params(VoiceEngine.SYSTEM).pitch, 0f)
        (VoiceEngine.entries - VoiceEngine.SYSTEM).forEach { assertEquals(1f, params(it, surprise).pitch, 0f) }
    }

    @Test
    fun onlyVoskGetsNoiseAndOnlyWithEmotions() {
        assertNull(params(VoiceEngine.SUPERTONIC).noise)
        assertNull(params(VoiceEngine.KOKORO).noise)
        assertNull(params(VoiceEngine.KOKORO_RU).noise)
        assertNull(params(VoiceEngine.SYSTEM).noise)
        val calm = params(VoiceEngine.VOSK)
        assertEquals(VoskNoise.CALM, calm.noise)
        // «Эмоции» are off: a shouted line still wavers like calm narration.
        assertEquals(VoskNoise.CALM, params(VoiceEngine.VOSK, emotions = EmotionLevel.OFF, role = SpeechRole.MALE, tone = SpeechTone.ANGRY).noise)
        val emotional = params(VoiceEngine.VOSK, emotions = EmotionLevel.VIVID, role = SpeechRole.MALE, tone = SpeechTone.ANGRY).noise
        assertEquals(0.75f, emotional!!.level, 0f)
        assertEquals(0.85f, emotional.durationLevel, 0f)
        // The narrator's own words stay plain even with emotions on.
        assertEquals(VoskNoise.CALM, params(VoiceEngine.VOSK, emotions = EmotionLevel.VIVID, role = SpeechRole.NARRATOR, tone = SpeechTone.ANGRY).noise)
        assertEquals(VoskNoise.CALM, params(VoiceEngine.VOSK, emotions = EmotionLevel.VIVID, role = SpeechRole.MALE, tone = SpeechTone.NEUTRAL).noise)
    }
}
