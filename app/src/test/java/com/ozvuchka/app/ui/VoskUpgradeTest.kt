package com.ozvuchka.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
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

/**
 * An install made before the fast Vosk graph offers a 226 MB upgrade in its model card; one that already
 * has model-fast.onnx does not, so a fresh install is never asked to download anything again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class VoskUpgradeTest {
    @get:Rule
    val compose = createComposeRule()

    private var upgrades = 0

    private val actions = object : VoiceSettingsActions {
        override fun selectVoice(language: String, voice: VoiceChoice) = Unit
        override fun preview(language: String, voice: VoiceChoice) = Unit
        override fun stopPreview() = Unit
        override fun download(model: SpeechModel) = Unit
        override fun upgrade(model: SpeechModel) {
            upgrades++
        }
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
        override fun setEmotions(level: EmotionLevel) = Unit
        override fun previewEmotions(language: String) = Unit
        override fun installRuVoice() = Unit
        override fun cancelRuVoice() = Unit
        override fun openRuVoicePage() = Unit
        override fun checkRuVoiceUpdate() = Unit
    }

    @Test
    fun anOldInstallOffersTheSmallUpgrade() {
        compose.setContent { VoiceSettingsSheet(state(upgradeable = true), "ru", actions) {} }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText(UPGRADE))
        compose.onNode(hasText(UPGRADE) and hasClickAction()).performSemanticsAction(SemanticsActions.OnClick)

        assertEquals(1, upgrades)
    }

    @Test
    fun anUpgradedInstallDoesNotOfferItAgain() {
        compose.setContent { VoiceSettingsSheet(state(upgradeable = false), "ru", actions) {} }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Голоса Vosk TTS"))
        compose.onNodeWithText(UPGRADE).assertDoesNotExist()
    }

    private fun state(upgradeable: Boolean) = VoiceSettingsUi(
        russianVoice = VoiceChoice(VoiceEngine.VOSK, 28),
        englishVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 5),
        speed = 1f,
        pauseScale = 1f,
        supertonicSteps = 10,
        preferFullModels = true,
        installedModels = setOf(SpeechModel.VOSK),
        upgradeableModels = if (upgradeable) setOf(SpeechModel.VOSK) else emptySet(),
    )

    private companion object {
        const val UPGRADE = "Ускорить запуск · 226 МБ"
    }
}
