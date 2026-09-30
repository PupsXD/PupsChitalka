package com.ozvuchka.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.EmotionLevel
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Эмоции в репликах» is switched on in the voice settings and plays its own sample. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class EmotionsCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val levels = mutableListOf<EmotionLevel>()
    private val samples = mutableListOf<String>()

    private val actions = object : VoiceSettingsActions {
        override fun selectVoice(language: String, voice: VoiceChoice) = Unit
        override fun preview(language: String, voice: VoiceChoice) = Unit
        override fun stopPreview() = Unit
        override fun download(model: SpeechModel) = Unit
        override fun cancelDownload(model: SpeechModel) = Unit
        override fun delete(model: SpeechModel) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setPauseScale(scale: Float) = Unit
        override fun setSupertonicSteps(steps: Int) = Unit
        override fun setPreferFullModels(enabled: Boolean) = Unit
        override fun openSystemTtsSettings() = Unit
        override fun openEngineApp(enginePackage: String) = Unit
        override fun loadEngineVoices(enginePackage: String) = Unit
        override fun refreshSystemVoices() = Unit
        override fun setDialogue(language: String, dialogue: DialogueVoices) = Unit
        override fun previewDialogue(language: String) = Unit
        override fun setEmotions(level: EmotionLevel) {
            levels += level
        }
        override fun previewEmotions(language: String) {
            samples += language
        }
        override fun installRuVoice() = Unit
        override fun cancelRuVoice() = Unit
        override fun openRuVoicePage() = Unit
        override fun checkRuVoiceUpdate() = Unit
    }

    private fun state(emotions: EmotionLevel) = VoiceSettingsUi(
        russianVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 0),
        englishVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 5),
        speed = 1f,
        pauseScale = 1f,
        supertonicSteps = 10,
        preferFullModels = true,
        installedModels = setOf(SpeechModel.SUPERTONIC),
        emotions = emotions,
    )

    @Test
    fun emotionsAreSwitchedOnAndSampled() {
        var current by mutableStateOf(state(EmotionLevel.OFF))
        compose.setContent { VoiceSettingsSheet(current, "ru", actions) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Эмоции в репликах"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Ярко"))
        compose.onNodeWithText("Прослушать пример").assertDoesNotExist()
        compose.onNodeWithText("Ярко").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf(EmotionLevel.VIVID), levels)

        current = state(EmotionLevel.VIVID)
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Прослушать пример"))
        compose.onNodeWithText("Прослушать пример").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("ru"), samples)
    }
}
