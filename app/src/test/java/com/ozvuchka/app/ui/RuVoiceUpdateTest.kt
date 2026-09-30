package com.ozvuchka.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.EmotionLevel
import com.ozvuchka.app.speech.RuVoiceInstallState
import com.ozvuchka.app.speech.RuVoiceUpdateState
import com.ozvuchka.app.speech.RuVoiceUpdateState.Stage
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.SystemEngineInfo
import com.ozvuchka.app.speech.VoiceCatalog
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** An installed RuVoice shows its version, finds out about a newer one and updates in place. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class RuVoiceUpdateTest {
    @get:Rule
    val compose = createComposeRule()

    private var installs = 0
    private var checks = 0
    private var pages = 0
    private var cancels = 0

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
        override fun setEmotions(level: EmotionLevel) = Unit
        override fun previewEmotions(language: String) = Unit
        override fun installRuVoice() {
            installs++
        }
        override fun cancelRuVoice() {
            cancels++
        }
        override fun openRuVoicePage() {
            pages++
        }
        override fun checkRuVoiceUpdate() {
            checks++
        }
    }

    private fun available(lite: Boolean = false) = RuVoiceUpdateState(
        Stage.AVAILABLE,
        installed = if (lite) "0.16.9-lite" else "0.16.9",
        latest = "0.17.1",
        size = 242_925_646,
        lite = lite,
    )

    private fun body(
        update: RuVoiceUpdateState,
        setup: RuVoiceInstallState = RuVoiceInstallState(),
        version: String? = update.installed,
    ) = compose.setContent { RuVoiceUpdateBody(version, update, setup, actions) }

    @Test
    fun aNewerVersionIsOfferedInTheSettingsAndInstalledOverTheOldOne() {
        val ruVoice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE)
        val state = VoiceSettingsUi(
            russianVoice = ruVoice,
            englishVoice = VoiceChoice(VoiceEngine.SUPERTONIC, 5),
            speed = 1f,
            pauseScale = 1f,
            supertonicSteps = 10,
            preferFullModels = true,
            systemEngines = listOf(SystemEngineInfo(VoiceCatalog.RUVOICE_PACKAGE, "RuVoice")),
            ruVoiceVersion = "0.16.9",
            ruVoiceUpdate = available(),
        )
        compose.setContent { VoiceSettingsSheet(state, "ru", actions) {} }
        // The engine list says so even when another card is in view.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Живая интонация · вышла новая версия 0.17.1"))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Обновить RuVoice"))
        compose.onNodeWithText("Версия RuVoice: 0.16.9").assertExists()
        compose.onNodeWithText("Вышла новая версия 0.17.1 (≈ 231 МБ)").assertExists()
        compose.onNodeWithText("Обновить RuVoice").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, installs)
    }

    @Test
    fun theReaderCanAskWhetherThereIsANewerVersion() {
        body(RuVoiceUpdateState(installed = "0.17.1"))
        compose.onNodeWithText("Версия RuVoice: 0.17.1").assertExists()
        compose.onNodeWithText("Обновить RuVoice").assertDoesNotExist()
        compose.onNodeWithText("Проверить обновления").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, checks)
    }

    @Test
    fun theLatestVersionIsSaidToBeTheLatest() {
        body(RuVoiceUpdateState(Stage.UP_TO_DATE, installed = "0.17.1", latest = "0.17.1"))
        compose.onNodeWithText("Версия RuVoice: 0.17.1 — самая свежая").assertExists()
        compose.onNodeWithText("Обновить RuVoice").assertDoesNotExist()
        compose.onNodeWithText("Проверить снова").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, checks)
    }

    @Test
    fun aFailedLookIsSaidSoAndCanBeRepeated() {
        body(RuVoiceUpdateState(Stage.FAILED, installed = "0.17.1", message = "Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз."))
        compose.onNodeWithText("Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз.").assertExists()
        compose.onNodeWithText("Повторить").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, checks)
    }

    @Test
    fun whileLookingNothingCanBePressed() {
        body(RuVoiceUpdateState(Stage.CHECKING, installed = "0.17.1"))
        compose.onNodeWithText("Проверяем, вышла ли новая версия…").assertExists()
        compose.onNodeWithText("Проверить обновления").assertDoesNotExist()
        compose.onNodeWithText("Обновить RuVoice").assertDoesNotExist()
    }

    @Test
    fun theLightBuildIsSentToTheReleasesPageInsteadOfBeingReplaced() {
        body(available(lite = true))
        compose.onNodeWithText("Версия RuVoice: 0.16.9-lite").assertExists()
        compose.onNodeWithText("Обновить RuVoice").assertDoesNotExist()
        compose.onNodeWithText("Открыть страницу релизов").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, pages)
        assertEquals(0, installs)
    }

    @Test
    fun theDownloadOfTheUpdateShowsProgressAndCanBeCancelled() {
        body(available(), RuVoiceInstallState(RuVoiceInstallState.Stage.DOWNLOADING, "0.17.1", 50L * 1024 * 1024, 231L * 1024 * 1024))
        compose.onNodeWithText("Скачиваем RuVoice 0.17.1: 50 из 231 МБ").assertExists()
        compose.onNodeWithText("Обновить RuVoice").assertDoesNotExist()
        compose.onNodeWithText("Отмена").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, cancels)
    }

    @Test
    fun aFailedUpdateCanBeRetriedOrDownloadedByHand() {
        body(available(), RuVoiceInstallState(RuVoiceInstallState.Stage.FAILED, "0.17.1", message = "Не хватает места для установки RuVoice."))
        compose.onNodeWithText("Не хватает места для установки RuVoice.").assertExists()
        compose.onNodeWithText("Повторить").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Скачать вручную").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(1, installs)
        assertEquals(1, pages)
    }
}
