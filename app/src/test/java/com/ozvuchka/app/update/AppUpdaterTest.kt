package com.ozvuchka.app.update

import com.ozvuchka.app.ui.appUpdateHeadline
import com.ozvuchka.app.ui.appUpdateShown
import com.ozvuchka.app.update.AppUpdateState.Stage
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdaterTest {
    /** The shape of api.github.com/repos/PupsXD/PupsChitalka/releases/latest, cut down. */
    private fun release(vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name": "v0.2.23555679", "name": "PupsChitalka 0.2 (2026-09-30 15:14 UTC)", "draft": $draft, "prerelease": $prerelease,
           "assets": [${assets.joinToString()}]}"""

    private fun asset(name: String, size: Long = 108_107_603, digest: String? = null) =
        """{"name": "$name", "size": $size, "digest": ${digest?.let { "\"$it\"" } ?: "null"},
           "browser_download_url": "https://github.com/PupsXD/PupsChitalka/releases/download/v0.2.23555679/$name"}"""

    @Test
    fun theAppIsFoundInTheLatestRelease() {
        val digest = "9311339689FFB0D7E3CC2268AEA44598E2265C95ECE6A8FD1E11A79851332B2D"
        val picked = AppUpdater.pickAppApk(release(asset("ozvuchka-23555679.apk", digest = "sha256:$digest")))!!
        assertEquals(23_555_679L, picked.versionCode)
        assertEquals(108_107_603L, picked.size)
        assertEquals(digest.lowercase(), picked.sha256)
        assertTrue(picked.url.endsWith("/ozvuchka-23555679.apk"))
        // Other files and releases that are not for everyone are not the app.
        assertNull(AppUpdater.pickAppApk(release(asset("notes.txt"), asset("ozvuchka-debug.apk"))))
        assertNull(AppUpdater.pickAppApk(release(asset("ozvuchka-23555679.apk"), prerelease = true)))
        assertNull(AppUpdater.pickAppApk(release(asset("ozvuchka-23555679.apk"), draft = true)))
        // A release without a digest is still used; the file's size and package are checked then.
        assertNull(AppUpdater.pickAppApk(release(asset("ozvuchka-23555679.apk")))!!.sha256)
    }

    private val latest = AppRelease(23_555_679, "https://github.com/x/ozvuchka-23555679.apk", 108_107_603, null)

    @Test
    fun aLaterBuildIsAnUpdateAndOnlyThat() {
        val older = AppUpdater.judge(23_400_000, latest)
        assertEquals(Stage.AVAILABLE, older.stage)
        assertEquals(23_555_679L, older.latest)
        assertEquals(108_107_603L, older.size)
        assertEquals(Stage.UP_TO_DATE, AppUpdater.judge(23_555_679, latest).stage)
        // A build made on a computer after the release is not old.
        assertEquals(Stage.UP_TO_DATE, AppUpdater.judge(23_600_000, latest).stage)
    }

    @Test
    fun githubIsAskedOnlyWhenItIsWorthIt() {
        val hour = 60L * 60 * 1000
        assertTrue(AppUpdater.checkDue(null, lastFailed = false, force = false))
        assertTrue(AppUpdater.checkDue(1_000, lastFailed = false, force = true))
        // Coming back to the app soon after a look, even after a restart, does not ask again...
        assertFalse(AppUpdater.checkDue(hour, lastFailed = false, force = false))
        assertTrue(AppUpdater.checkDue(7 * hour, lastFailed = false, force = false))
        // ...a failed look is retried after a short wait, and a clock set back does not stop the looks.
        assertFalse(AppUpdater.checkDue(30_000, lastFailed = true, force = false))
        assertTrue(AppUpdater.checkDue(3 * 60_000, lastFailed = true, force = false))
        assertTrue(AppUpdater.checkDue(-hour, lastFailed = false, force = false))
    }

    @Test
    fun theVersionIsShownAsTheDayItWasBuilt() {
        // The release above was built at 2026-09-30 15:14 UTC; the reader sees their own time.
        assertEquals("30 сентября 2026, 15:14", AppUpdater.versionDate(23_555_679, ZoneId.of("UTC")))
        assertEquals("30 сентября 2026, 18:14", AppUpdater.versionDate(23_555_679, ZoneId.of("Europe/Moscow")))
    }

    @Test
    fun theCardSaysWhereTheUpdateIs() {
        val date = { code: Long -> "#$code" }
        assertEquals("Вышла новая версия от #200 (≈ 103 МБ)", appUpdateHeadline(AppUpdateState(Stage.AVAILABLE, 100, 200, 108_107_603), date))
        assertEquals(
            "Скачиваем обновление: 34 из 103 МБ",
            appUpdateHeadline(AppUpdateState(Stage.DOWNLOADING, 100, 200, 108_107_603, 36_000_000, 108_107_603), date),
        )
        assertEquals("Скачиваем обновление", appUpdateHeadline(AppUpdateState(Stage.DOWNLOADING, 100, 200), date))
        assertEquals("У вас самая свежая версия — от #200", appUpdateHeadline(AppUpdateState(Stage.UP_TO_DATE, 200, 200), date))
    }

    @Test
    fun theLibraryShowsTheCardOnlyWhenThereIsSomethingToSay() {
        val available = AppUpdateState(Stage.AVAILABLE, 100, 200)
        assertTrue(appUpdateShown(available, asked = false, dismissed = 0))
        // «Не сейчас» puts off this version, not the next one; asking brings it back.
        assertFalse(appUpdateShown(available, asked = false, dismissed = 200))
        assertTrue(appUpdateShown(available.copy(latest = 300), asked = false, dismissed = 200))
        assertTrue(appUpdateShown(available, asked = true, dismissed = 200))
        // An update under way stays on screen.
        assertTrue(appUpdateShown(available.copy(stage = Stage.DOWNLOADING), asked = false, dismissed = 200))
        assertTrue(appUpdateShown(available.copy(stage = Stage.CONFIRMING), asked = false, dismissed = 0))
        // Nothing new, a look in progress or a failed look show only when the reader asked.
        assertFalse(appUpdateShown(AppUpdateState(Stage.UP_TO_DATE, 200, 200), asked = false, dismissed = 0))
        assertTrue(appUpdateShown(AppUpdateState(Stage.UP_TO_DATE, 200, 200), asked = true, dismissed = 0))
        assertFalse(appUpdateShown(AppUpdateState(Stage.CHECK_FAILED, 200), asked = false, dismissed = 0))
        assertFalse(appUpdateShown(AppUpdateState(Stage.CHECKING, 200), asked = false, dismissed = 0))
        assertFalse(appUpdateShown(AppUpdateState(), asked = true, dismissed = 0))
    }
}
