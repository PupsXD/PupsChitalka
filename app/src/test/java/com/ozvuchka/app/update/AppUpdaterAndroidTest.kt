package com.ozvuchka.app.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import com.ozvuchka.app.update.AppUpdateState.Stage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What the app does with Android's answers about the update, and how it learns on the next launch that it went in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppUpdaterAndroidTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs get() = context.getSharedPreferences("app_update", Context.MODE_PRIVATE)

    private fun answer(status: Int, extra: Intent.() -> Unit = {}) =
        AppUpdater.onInstallResult(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, status).apply(extra))

    @Test
    fun theNextLaunchTellsTheUpdateWentIn() {
        val installed = AppUpdater.installedVersion(context)
        assertTrue(installed > 0)
        val download = File(context.cacheDir, "app-update/ozvuchka-$installed.apk").apply { parentFile!!.mkdirs(); writeText("apk") }
        prefs.edit().putLong("installing", installed).commit()
        assertEquals(installed, AppUpdater.start(context))
        assertFalse("the downloaded file is not needed any more", download.exists())
        // Said once.
        assertNull(AppUpdater.start(context))
        // An update the reader did not finish is not announced.
        prefs.edit().putLong("installing", installed + 1000).commit()
        assertNull(AppUpdater.start(context))
    }

    @Test
    fun androidsAnswersMoveTheOfferOn() {
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        answer(PackageInstaller.STATUS_PENDING_USER_ACTION) { putExtra(Intent.EXTRA_INTENT, confirm) }
        assertEquals(Stage.CONFIRMING, AppUpdater.state.value.stage)
        assertEquals(confirm.action, AppUpdater.takeConfirmation()?.action)
        // Shown once.
        assertNull(AppUpdater.takeConfirmation())

        prefs.edit().putLong("installing", 1).commit()
        answer(PackageInstaller.STATUS_FAILURE_ABORTED)
        assertEquals(Stage.AVAILABLE, AppUpdater.state.value.stage)
        assertFalse(prefs.contains("installing"))

        answer(PackageInstaller.STATUS_FAILURE_CONFLICT)
        assertEquals(Stage.INSTALL_FAILED, AppUpdater.state.value.stage)
        assertTrue(AppUpdater.state.value.message!!.contains("разные подписи"))

        answer(PackageInstaller.STATUS_FAILURE_BLOCKED)
        assertTrue(AppUpdater.state.value.message!!.contains("Разрешите PupsChitalka устанавливать приложения"))
    }
}
