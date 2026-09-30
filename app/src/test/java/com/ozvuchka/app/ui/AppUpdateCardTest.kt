package com.ozvuchka.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ozvuchka.app.update.AppUpdateState
import com.ozvuchka.app.update.AppUpdateState.Stage
import com.ozvuchka.app.update.AppUpdater
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The library offers a new version of the app and walks the reader through updating it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp")
class AppUpdateCardTest {
    @get:Rule
    val compose = createComposeRule()

    private var installs = 0
    private var cancels = 0
    private var checks = 0
    private var dismissals = 0
    private var pages = 0

    private val actions = object : AppUpdateActions {
        override fun installUpdate() {
            installs++
        }
        override fun cancelUpdate() {
            cancels++
        }
        override fun checkUpdate() {
            checks++
        }
        override fun dismissUpdate() {
            dismissals++
        }
        override fun openReleasesPage() {
            pages++
        }
    }

    private var state by mutableStateOf(AppUpdateState())

    private fun library() = compose.setContent {
        OzvuchkaTheme {
            LibraryScreen(
                books = emptyList(),
                narratingBookId = null,
                narrationPlaying = false,
                onOpenBook = {},
                onListen = {},
                onImportFile = {},
                onImportUrl = {},
                onDeleteBook = {},
                onOpenVoices = {},
                appUpdate = state.takeIf { appUpdateShown(it, asked = false, dismissed = 0) },
                appUpdateActions = actions,
                appVersion = "Версия от 1 сентября 2026, 12:00",
            )
        }
    }

    @Test
    fun aNewVersionIsOfferedAndDownloaded() {
        state = AppUpdateState(Stage.AVAILABLE, installed = 20_000_000, latest = 23_555_679, size = 108_107_603)
        library()
        compose.onNodeWithText("Вышла новая версия от ${AppUpdater.versionDate(23_555_679)} (≈ 103 МБ)").assertExists()
        compose.onNodeWithText("Книги, закладки и настройки сохранятся", substring = true).assertExists()
        compose.onNodeWithText("Обновить").performClick()
        assertEquals(1, installs)
        compose.onNodeWithText("Не сейчас").performClick()
        assertEquals(1, dismissals)

        state = state.copy(stage = Stage.DOWNLOADING, completed = 36_000_000, total = 108_107_603)
        compose.onNodeWithText("Скачиваем обновление: 34 из 103 МБ").assertExists()
        compose.onNodeWithText("Отмена").performClick()
        assertEquals(1, cancels)

        state = state.copy(stage = Stage.CONFIRMING)
        compose.onNodeWithText("Устанавливаем обновление…").assertExists()
        compose.onNodeWithText("откройте его снова", substring = true).assertExists()
    }

    @Test
    fun aFailedUpdateCanBeRetriedOrDoneByHand() {
        state = AppUpdateState(
            Stage.INSTALL_FAILED,
            installed = 20_000_000,
            latest = 23_555_679,
            message = "Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз.",
        )
        library()
        compose.onNodeWithText("Не получилось обновить приложение").assertExists()
        compose.onNodeWithText("Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз.").assertExists()
        compose.onNodeWithText("Повторить").performClick()
        compose.onNodeWithText("Скачать вручную").performClick()
        compose.onNodeWithText("Не сейчас").performClick()
        assertEquals(listOf(1, 1, 1), listOf(installs, pages, dismissals))
    }

    @Test
    fun theMenuChecksForUpdatesAndShowsTheVersion() {
        library()
        compose.onNodeWithText("Вышла новая версия", substring = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("О приложении").performClick()
        compose.onNodeWithText("Версия от 1 сентября 2026, 12:00").assertIsNotEnabled()
        compose.onNodeWithText("Проверить обновления").performClick()
        assertEquals(1, checks)
    }
}
