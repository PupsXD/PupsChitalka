package com.ozvuchka.app.speech

import android.content.Context

enum class VoiceEngine { SUPERTONIC, KOKORO, SYSTEM }

/**
 * A voice as stored in preferences: `supertonic:3`, `kokoro:3` or
 * `system:<engine package>:<voice name>`.
 */
data class VoiceChoice(
    val engine: VoiceEngine,
    val speaker: Int = 0,
    val enginePackage: String = "",
    val voiceName: String = "",
) {
    fun encode(): String = when (engine) {
        VoiceEngine.SUPERTONIC -> "supertonic:$speaker"
        VoiceEngine.KOKORO -> "kokoro:$speaker"
        VoiceEngine.SYSTEM -> "system:$enginePackage:$voiceName"
    }

    companion object {
        fun decode(value: String?): VoiceChoice? {
            if (value.isNullOrBlank()) return null
            val parts = value.split(':', limit = 3)
            return when (parts[0]) {
                "supertonic" -> parts.getOrNull(1)?.toIntOrNull()?.let { VoiceChoice(VoiceEngine.SUPERTONIC, it.coerceIn(0, 9)) }
                "kokoro" -> parts.getOrNull(1)?.toIntOrNull()?.let { VoiceChoice(VoiceEngine.KOKORO, it.coerceAtLeast(0)) }
                "system" -> if (parts.size == 3 && parts[1].isNotBlank()) {
                    VoiceChoice(VoiceEngine.SYSTEM, enginePackage = parts[1], voiceName = parts[2])
                } else null
                else -> null
            }
        }
    }
}

/** A built-in voice preset shown in the voice picker. */
data class VoicePreset(
    val choice: VoiceChoice,
    val title: String,
    val description: String,
    val recommended: Boolean = false,
)

object VoiceCatalog {
    /** RuVoice wraps Silero v5 as a system engine: the most natural fast Russian voice on Android today. */
    const val RUVOICE_PACKAGE = "ru.kost.ruvoice"
    const val RUVOICE_RELEASES = "https://github.com/kost-t-human/ruvoice-tts/releases"

    // voice.bin is built from sorted style files: F1…F5 then M1…M5.
    val supertonic: List<VoicePreset> = (0 until 10).map { sid ->
        val female = sid < 5
        VoicePreset(
            choice = VoiceChoice(VoiceEngine.SUPERTONIC, sid),
            title = if (female) "Женский ${sid + 1}" else "Мужской ${sid - 4}",
            description = if (female) "Supertonic · F${sid + 1}" else "Supertonic · M${sid - 4}",
            recommended = sid == 0 || sid == 5,
        )
    }

    /** The best-rated Kokoro v1.0 English voices; IDs follow sherpa-onnx voices.bin. */
    val kokoro: List<VoicePreset> = listOf(
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 3), "Heart", "American · female · A", recommended = true),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 2), "Bella", "American · female · A−"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 6), "Nicole", "American · female · soft"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 21), "Emma", "British · female · B−"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 9), "Sarah", "American · female"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 5), "Kore", "American · female"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 16), "Michael", "American · male", recommended = true),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 14), "Fenrir", "American · male"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 18), "Puck", "American · male"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 26), "George", "British · male"),
        VoicePreset(VoiceChoice(VoiceEngine.KOKORO, 25), "Fable", "British · male"),
    )

    /** British Kokoro voices are trained on en-gb phonemes. */
    fun kokoroLanguage(speaker: Int): String = if (speaker in 20..27) "en-gb" else "en-us"

    fun presetTitle(choice: VoiceChoice): String? =
        (supertonic + kokoro).firstOrNull { it.choice == choice }?.let {
            when (choice.engine) {
                VoiceEngine.SUPERTONIC -> "Supertonic · ${it.title}"
                VoiceEngine.KOKORO -> "Kokoro · ${it.title}"
                VoiceEngine.SYSTEM -> it.title
            }
        }
}

/** How characters' lines are voiced in one language. */
enum class DialogueMode { OFF, SINGLE, BY_GENDER }

data class DialogueVoices(
    val mode: DialogueMode = DialogueMode.OFF,
    /** Every character's line, in [DialogueMode.SINGLE]. */
    val single: VoiceChoice? = null,
    val male: VoiceChoice? = null,
    val female: VoiceChoice? = null,
) {
    /** A line whose speaker is unknown stays with the narrator rather than risk the wrong gender. */
    fun voiceFor(role: SpeechRole, narrator: VoiceChoice): VoiceChoice = when (mode) {
        DialogueMode.OFF -> narrator
        DialogueMode.SINGLE -> if (role == SpeechRole.NARRATOR) narrator else single ?: narrator
        DialogueMode.BY_GENDER -> when (role) {
            SpeechRole.MALE -> male ?: narrator
            SpeechRole.FEMALE -> female ?: narrator
            else -> narrator
        }
    }

    /**
     * The same mode with voices that sound apart: a character voice that is the narrator's, or not
     * one of [options], is replaced by the first voice of the right gender that nobody else uses.
     * [narrator] is the voice narration really sounds with — for an engine's default, the voice
     * behind it.
     */
    fun withDistinctVoices(options: List<RoleVoice>, narrator: VoiceChoice): DialogueVoices {
        fun kept(choice: VoiceChoice?, vararg taken: VoiceChoice?) =
            choice?.takeIf { picked -> options.any { it.choice == picked } && picked !in taken }
        fun pick(gender: SpeechRole?, vararg taken: VoiceChoice?) =
            options.firstOrNull { (gender == null || it.gender == gender) && it.choice !in taken }?.choice
        return when (mode) {
            DialogueMode.OFF -> this
            DialogueMode.SINGLE -> copy(single = kept(single, narrator) ?: pick(null, narrator))
            DialogueMode.BY_GENDER -> {
                val man = kept(male, narrator) ?: pick(SpeechRole.MALE, narrator)
                copy(male = man, female = kept(female, narrator, man) ?: pick(SpeechRole.FEMALE, narrator, man))
            }
        }
    }

    /**
     * Character voices that sound like the narrator, so their lines would not stand out: [SpeechRole.MALE]
     * and [SpeechRole.FEMALE] by role, [SpeechRole.SPEECH] for the one voice of every line.
     */
    fun sameAsNarrator(narrator: VoiceChoice): List<SpeechRole> = when (mode) {
        DialogueMode.OFF -> emptyList()
        DialogueMode.SINGLE -> listOfNotNull(SpeechRole.SPEECH.takeIf { single == null || single == narrator })
        DialogueMode.BY_GENDER -> listOfNotNull(
            SpeechRole.MALE.takeIf { male == null || male == narrator },
            SpeechRole.FEMALE.takeIf { female == null || female == narrator },
        )
    }
}

/** A voice that can read characters' lines, with its gender when the voice's name tells it. */
data class RoleVoice(val choice: VoiceChoice, val title: String, val gender: SpeechRole?)

/** Narration preferences shared by the reader UI and the playback service. */
data class SpeechSettings(
    val russianVoice: VoiceChoice,
    val englishVoice: VoiceChoice,
    val speed: Float,
    val pauseScale: Float,
    val supertonicSteps: Int,
    val preferFullModels: Boolean,
    val russianDialogue: DialogueVoices = DialogueVoices(),
    val englishDialogue: DialogueVoices = DialogueVoices(),
) {
    fun voiceFor(language: String): VoiceChoice = if (language == "en") englishVoice else russianVoice

    fun dialogueFor(language: String): DialogueVoices = if (language == "en") englishDialogue else russianDialogue

    /** The voice for a segment: the narrator, or a character voice when dialogue voices are on. */
    fun voiceFor(language: String, role: SpeechRole): VoiceChoice = dialogueFor(language).voiceFor(role, voiceFor(language))

    /** Characters' lines are cut into their own segments only when some language voices them apart. */
    val splitsDialogue: Boolean
        get() = russianDialogue.mode != DialogueMode.OFF || englishDialogue.mode != DialogueMode.OFF

    companion object {
        private const val PREFS = "reader"

        fun load(context: Context): SpeechSettings {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            // Older builds stored one Supertonic speaker for every language.
            val legacySpeaker = prefs.getInt("voiceId", 0).coerceIn(0, 9)
            val russian = VoiceChoice.decode(prefs.getString("voiceRu", null))
                ?: VoiceChoice(VoiceEngine.SUPERTONIC, legacySpeaker)
            val english = VoiceChoice.decode(prefs.getString("voiceEn", null))
                ?: if (SpeechModels.isInstalled(context, SpeechModel.KOKORO) ||
                    SpeechModels.isInstalled(context, SpeechModel.KOKORO_FULL)
                ) {
                    VoiceChoice(VoiceEngine.KOKORO, 3)
                } else {
                    VoiceChoice(VoiceEngine.SUPERTONIC, legacySpeaker)
                }
            return SpeechSettings(
                russianVoice = russian,
                englishVoice = english,
                speed = prefs.getFloat("speechSpeed", 1f).coerceIn(0.5f, 2.5f),
                pauseScale = prefs.getFloat("pauseScale", 1f).coerceIn(0.4f, 2f),
                supertonicSteps = prefs.getInt("supertonicSteps", 10).coerceIn(4, 32),
                preferFullModels = prefs.getBoolean("preferFullVoice", true),
                russianDialogue = loadDialogue(prefs, "Ru"),
                englishDialogue = loadDialogue(prefs, "En"),
            )
        }

        private fun loadDialogue(prefs: android.content.SharedPreferences, suffix: String) = DialogueVoices(
            mode = runCatching { DialogueMode.valueOf(prefs.getString("dialogueMode$suffix", null) ?: "OFF") }
                .getOrDefault(DialogueMode.OFF),
            single = VoiceChoice.decode(prefs.getString("dialogueVoice$suffix", null)),
            male = VoiceChoice.decode(prefs.getString("dialogueMale$suffix", null)),
            female = VoiceChoice.decode(prefs.getString("dialogueFemale$suffix", null)),
        )

        private fun android.content.SharedPreferences.Editor.putDialogue(suffix: String, dialogue: DialogueVoices) = apply {
            putString("dialogueMode$suffix", dialogue.mode.name)
            putString("dialogueVoice$suffix", dialogue.single?.encode())
            putString("dialogueMale$suffix", dialogue.male?.encode())
            putString("dialogueFemale$suffix", dialogue.female?.encode())
        }

        fun save(context: Context, settings: SpeechSettings) {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("voiceRu", settings.russianVoice.encode())
                .putString("voiceEn", settings.englishVoice.encode())
                .putFloat("speechSpeed", settings.speed)
                .putFloat("pauseScale", settings.pauseScale)
                .putInt("supertonicSteps", settings.supertonicSteps)
                .putBoolean("preferFullVoice", settings.preferFullModels)
                .putDialogue("Ru", settings.russianDialogue)
                .putDialogue("En", settings.englishDialogue)
                .apply()
        }
    }
}
