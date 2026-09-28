package com.ozvuchka.app.speech

import com.ozvuchka.app.speech.SpeechTone.ANGRY
import com.ozvuchka.app.speech.SpeechTone.COLD
import com.ozvuchka.app.speech.SpeechTone.EXCLAIM
import com.ozvuchka.app.speech.SpeechTone.FEAR
import com.ozvuchka.app.speech.SpeechTone.JOY
import com.ozvuchka.app.speech.SpeechTone.NEUTRAL
import com.ozvuchka.app.speech.SpeechTone.QUIET
import com.ozvuchka.app.speech.SpeechTone.SAD
import com.ozvuchka.app.speech.SpeechTone.SURPRISE
import com.ozvuchka.app.speech.SpeechTone.TENDER
import com.ozvuchka.app.speech.SpeechTone.UNSURE
import com.ozvuchka.app.speech.SpeechTone.WHISPER
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** How a character's line is said, from the author's words and the line's own marks. */
class ToneTest {
    /** The tone of every sentence of the characters' lines, in reading order. */
    private fun tones(vararg paragraphs: String): List<SpeechTone> =
        chapterSegments(0, null, paragraphs.toList(), splitDialogue = true)
            .filter { it.role != SpeechRole.NARRATOR }
            .map { it.tone }

    private fun toneOf(paragraph: String): SpeechTone = tones(paragraph).distinct().single()

    @Test
    fun theAuthorsWordsTellHowALineIsSaid() {
        assertEquals(WHISPER, toneOf("— Уходи, — прошептала она."))
        assertEquals(ANGRY, toneOf("— Вон отсюда! — рявкнул он."))
        assertEquals(UNSURE, toneOf("— Я не знаю, — неуверенно ответила она."))
        assertEquals(COLD, toneOf("— Не смей, — процедил он сквозь зубы."))
        assertEquals(SAD, toneOf("— Прости меня, — сказала она сквозь слёзы."))
        assertEquals(JOY, toneOf("— Мы победили! — радостно воскликнул мальчишка."))
        assertEquals(FEAR, toneOf("— Что это было? — испуганно спросила она."))
        assertEquals(TENDER, toneOf("— Иди сюда, — ласково позвала мать."))
        assertEquals(SURPRISE, toneOf("— Ты здесь? — удивлённо спросил он."))
        assertEquals(QUIET, toneOf("— Спит, — тихо сказала медсестра."))
        assertEquals(QUIET, toneOf("— Он здесь, — понизив голос, сообщил Вэй Усянь."))
        assertEquals(EXCLAIM, toneOf("— Сюда, — громко позвал Цзян Чэн."))
        // «её голос дрогнул» after «, и» is still about the line.
        assertEquals(SAD, toneOf("— Я не вернусь, — сказала она, и голос её дрогнул."))
        // A name before the verb after an exclamation: «— Стой! — Лань Чжань крикнул».
        assertEquals(ANGRY, toneOf("— Стой! — Лань Чжань крикнул."))
    }

    @Test
    fun theAuthorsWordsHoldForTheWholeLine() {
        assertEquals(listOf(WHISPER, WHISPER), tones("— Уходи, — прошептала она, — пока не поздно."))
        assertEquals(listOf(ANGRY, ANGRY), tones("— Стой! — крикнул он. — Не стреляй!"))
        assertEquals(listOf(COLD, COLD), tones("— Я пришёл сказать, что всё кончено. Больше не приходи, — холодно сказал он."))
        assertEquals(
            listOf(WHISPER, WHISPER),
            tones("— Тише, — прошептала сестра Цзян Чэна, косясь на дверь. — Матушка вернулась с совета не в духе."),
        )
        // Something else happened between the halves: she shouted at others, then turned to him.
        assertEquals(
            listOf(ANGRY, NEUTRAL),
            tones("— Разговорчики! — прикрикнула Карина и снова повернулась к участковому. — Давайте я вам лёд принесу."),
        )
        // A new sentence between the halves tells about the second one.
        assertEquals(listOf(NEUTRAL, WHISPER), tones("— Нет, — сказала она. Потом, помолчав, добавила шёпотом: — Уходи."))
        assertEquals(listOf(NEUTRAL, ANGRY), tones("— Сгоряча? — Её голос зазвенел от гнева. — Они сожгли Облачные Глубины!"))
        // Words before the line lead into it.
        assertEquals(JOY, toneOf("Сюэ Ян расхохотался. — Даочжан, ты шутишь?"))
        assertEquals(listOf(ANGRY, ANGRY), tones("Он вскочил и закричал: — Стой! Не стреляй!"))
        assertEquals(SAD, toneOf("Цзян Яньли, всё ещё всхлипывая, подняла на него глаза. — Я не хотела."))
        // A sob right after the line is how it was said.
        assertEquals(SAD, toneOf("— Нет. — Она всхлипнула."))
    }

    @Test
    fun wordsAboutSomethingElseDoNotCount() {
        assertEquals(NEUTRAL, toneOf("— Уходи, — сказал он, не повышая голоса."))
        assertEquals(NEUTRAL, toneOf("— Уходи, — сказал он не очень громко."))
        assertEquals(NEUTRAL, toneOf("— Нет, — сказал он, и она заплакала."))
        assertEquals(NEUTRAL, toneOf("— Нет, — ответила она, вспоминая, как он кричал на неё вчера."))
        assertEquals(listOf(ANGRY, NEUTRAL), tones("— Стой! — крикнул он, и она обернулась. — Что тебе?"))
        assertEquals(NEUTRAL, toneOf("Когда он закричал, она вздрогнула. — Ты чего?"))
        assertEquals(NEUTRAL, toneOf("Она весело болтала весь вечер, но потом устала и замолчала. — Пойдём домой."))
        // The room, not the voice: «Стало тихо».
        assertEquals(NEUTRAL, toneOf("Стало тихо. — Он спит?"))
        // A new sentence after the line describes the line only when it says how it was said.
        assertEquals(NEUTRAL, toneOf("— Уходи. — Она тихо вышла из комнаты."))
    }

    @Test
    fun theLineItselfShowsItsTone() {
        assertEquals(EXCLAIM, toneOf("— Стой!"))
        assertEquals(ANGRY, toneOf("— СТОЯТЬ!"))
        assertEquals(ANGRY, toneOf("— Вон!!"))
        assertEquals(SURPRISE, toneOf("— Что?!"))
        assertEquals(UNSURE, toneOf("— Я… я не знаю."))
        assertEquals(UNSURE, toneOf("— П-простите."))
        assertEquals(UNSURE, toneOf("— Э-э, ну, как сказать."))
        // The voice raises a question by itself.
        assertEquals(NEUTRAL, toneOf("— Ты придёшь завтра?"))
        // Sentence by sentence: only the exclamation is louder.
        assertEquals(listOf(NEUTRAL, EXCLAIM), tones("— Я не пойду. Уходи!"))
        // An exclamation outweighs a pause: indignation, not doubt.
        assertEquals(EXCLAIM, toneOf("— Ты… да как ты смеешь!"))
        // A pause in the middle of a line hesitates; a line trailing off or opening with an ellipsis does not.
        assertEquals(listOf(UNSURE, NEUTRAL), tones("— Глава ордена, простите... У ворот ждёт посланник из Цинхэ."))
        assertEquals(NEUTRAL, toneOf("— Голодная? Там суп, котлеты..."))
        assertEquals(NEUTRAL, toneOf("— ...и наконец, отказ от участия в облаве."))
        // Hyphenated names are not stutters.
        assertEquals(NEUTRAL, toneOf("— А-Цин, иди сюда."))
        assertEquals(NEUTRAL, toneOf("— Линь-сюн, проходите."))
    }

    @Test
    fun englishLinesToo() {
        assertEquals(ANGRY, toneOf("“Get out!” he shouted."))
        assertEquals(ANGRY, toneOf("“Stop!” Lisa shouted."))
        assertEquals(WHISPER, toneOf("“I’m sorry,” she whispered."))
        assertEquals(UNSURE, toneOf("“I-I don’t know,” he said."))
        assertEquals(COLD, toneOf("“Fine,” she said coldly."))
        assertEquals(JOY, toneOf("She suddenly laughed. “Silly! I’m not angry.”"))
        assertEquals(NEUTRAL, toneOf("“Fine,” she said, not angrily at all."))
    }

    @Test
    fun theNarratorReadsPlainly() {
        val segments = chapterSegments(
            0, null,
            listOf("Он закричал так, что задрожали стёкла!", "— Стой! — крикнул он.", "Она испуганно замерла."),
            splitDialogue = true,
        )
        assertTrue(segments.filter { it.role == SpeechRole.NARRATOR }.all { it.tone == NEUTRAL })
        assertEquals(listOf(ANGRY), segments.filter { it.role != SpeechRole.NARRATOR }.map { it.tone })
        // Without dialogue splitting nothing is a line.
        assertTrue(chapterSegments(0, null, listOf("— Стой! — крикнул он.")).all { it.tone == NEUTRAL })
    }

    @Test
    fun theSettingDecidesHowStronglyLinesShowTheirTone() {
        assertEquals(Prosody.NEUTRAL, ANGRY.prosody(0f))
        assertEquals(Prosody.NEUTRAL, NEUTRAL.prosody(1f))
        val full = WHISPER.prosody(1f)
        val half = WHISPER.prosody(0.5f)
        assertTrue(full.gain < half.gain && half.gain < 1f)
        assertTrue(full.rate < half.rate && half.rate < 1f)
        assertTrue(ANGRY.prosody(1f).let { it.gain > 1f && it.rate > 1f && it.pitch > 1f })

        val settings = SpeechSettings(
            russianVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 0),
            englishVoice = VoiceChoice(VoiceEngine.KOKORO, 3),
            speed = 1f,
            pauseScale = 1f,
            supertonicSteps = 10,
            preferFullModels = true,
            emotions = EmotionLevel.VIVID,
        )
        val line = SpeechSegment(0, 0, 2, 7, "Стой!", "ru", SegmentPause.SENTENCE, SpeechRole.MALE, ANGRY)
        assertEquals(ANGRY.prosody(1f), settings.prosodyFor(line))
        assertEquals(ANGRY.prosody(0.5f), settings.copy(emotions = EmotionLevel.SUBTLE).prosodyFor(line))
        assertEquals(Prosody.NEUTRAL, settings.copy(emotions = EmotionLevel.OFF).prosodyFor(line))
        assertEquals(Prosody.NEUTRAL, settings.prosodyFor(line.copy(role = SpeechRole.NARRATOR)))
        // Emotions alone cut lines out of the text, but the characters' names are not needed for them.
        assertTrue(settings.splitsDialogue)
        assertFalse(settings.voicesCharacters)
        assertFalse(settings.copy(emotions = EmotionLevel.OFF).splitsDialogue)
    }

    @Test
    fun aVoiceWithoutPitchControlIsRaisedByPlayingItFaster() {
        val rate = 24_000
        val tone = FloatArray(rate) { sin(2 * PI * 200 * it / rate).toFloat() * 0.5f }
        // Rendered 8% slower, played 8% faster: the same length, 8% higher.
        val raised = Resampler.resample(tone, (rate * 1.08f).toInt(), rate)
        assertEquals(rate / 1.08f, raised.size.toFloat(), 2f)
        val crossings = (1 until raised.size).count { raised[it - 1] < 0f && raised[it] >= 0f }
        val frequency = crossings / (raised.size / rate.toDouble())
        assertEquals(216.0, frequency, 2.0)
    }
}
