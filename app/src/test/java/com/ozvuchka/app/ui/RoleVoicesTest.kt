package com.ozvuchka.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.ozvuchka.app.speech.DialogueMode
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «По ролям» shows the narrator, the men's and the women's voices, and keeps them apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class RoleVoicesTest {
    @get:Rule
    val compose = createComposeRule()

    private val saved = mutableListOf<DialogueVoices>()

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
        override fun setDialogue(language: String, dialogue: DialogueVoices) {
            saved += dialogue
        }
        override fun previewDialogue(language: String) = Unit
        override fun installRuVoice() = Unit
        override fun cancelRuVoice() = Unit
        override fun openRuVoicePage() = Unit
    }

    @Test
    fun aRoleInTheNarratorsVoiceIsMovedToAnother() {
        val narrator = VoiceChoice(VoiceEngine.SUPERTONIC, 0)
        val state = VoiceSettingsUi(
            russianVoice = narrator,
            englishVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 5),
            speed = 1f,
            pauseScale = 1f,
            supertonicSteps = 10,
            preferFullModels = true,
            installedModels = setOf(SpeechModel.SUPERTONIC),
            // The women's roles read with the narrator's own voice.
            russianDialogue = DialogueVoices(DialogueMode.BY_GENDER, male = VoiceChoice(VoiceEngine.SUPERTONIC, 5), female = narrator),
        )
        compose.setContent { VoiceSettingsSheet(state, "ru", actions) {} }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Голоса персонажей"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Развести"))
        compose.onNodeWithText("Рассказчик").assertExists()
        compose.onNodeWithText("Мужские роли").assertExists()
        compose.onNodeWithText("Женские роли").assertExists()
        compose.onNodeWithText("Женские роли звучат голосом рассказчика — переход к диалогу не будет слышен.").assertExists()
        compose.onNodeWithText("Развести").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(VoiceChoice(VoiceEngine.SUPERTONIC, 1), saved.last().female)
        assertEquals(VoiceChoice(VoiceEngine.SUPERTONIC, 5), saved.last().male)
    }
}
