package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuVoiceInstallerTest {
    private fun asset(name: String, size: Long = 240_000_000, digest: String? = null) =
        """{"name": "$name", "size": $size, "browser_download_url": "https://github.com/kost-t-human/ruvoice-tts/releases/download/x/$name", "digest": ${digest?.let { "\"$it\"" } ?: "null"}}"""

    private fun release(tag: String, vararg assets: String, draft: Boolean = false, prerelease: Boolean = false) =
        """{"tag_name": "$tag", "draft": $draft, "prerelease": $prerelease, "assets": [${assets.joinToString()}]}"""

    @Test
    fun picksTheNewestFullBuild() {
        val digest = "A".repeat(64)
        val json = "[" + listOf(
            // The voice packs live in a release of their own and hold no APK.
            release("packs", asset("ruvoice-pack-ru.zip", 83_000_000), asset("ruvoice-pack-cis_ru.zip")),
            release("v0.18.0", asset("ruvoice-tts-0.18.0.apk"), draft = true),
            release("v0.17.0", asset("ruvoice-tts-0.17.0.apk"), prerelease = true),
            release("v0.16.9", asset("ruvoice-tts-0.16.9.apk"), asset("ruvoice-tts-0.16.9-lite.apk")),
            release("v0.16.10", asset("ruvoice-tts-0.16.10-lite.apk", 150_000_000), asset("ruvoice-tts-0.16.10.apk", 241_234_567, "sha256:$digest")),
        ).joinToString() + "]"
        val picked = RuVoiceInstaller.pickRuVoiceApk(json)!!
        assertEquals("0.16.10", picked.version)
        assertEquals(241_234_567L, picked.size)
        assertEquals("a".repeat(64), picked.sha256)
        assertTrue(picked.url.endsWith("/ruvoice-tts-0.16.10.apk"))
    }

    @Test
    fun missingDigestOrApkIsHandled() {
        val withoutDigest = "[" + release("v0.16.2", asset("ruvoice-tts-0.16.2.apk")) + "]"
        assertNull(RuVoiceInstaller.pickRuVoiceApk(withoutDigest)!!.sha256)
        val onlyLite = "[" + release("v0.16.2", asset("ruvoice-tts-0.16.2-lite.apk"), asset("ruvoice-pack-ru.zip")) + "]"
        assertNull(RuVoiceInstaller.pickRuVoiceApk(onlyLite))
        assertNull(RuVoiceInstaller.pickRuVoiceApk("[]"))
    }

    private fun latest(version: String) = RuVoiceRelease(version, "https://github.com/x/ruvoice-tts-$version.apk", 242_925_646, null)

    @Test
    fun theVersionNumberIsReadFromTheVersionName() {
        assertEquals("0.17.1", RuVoiceInstaller.versionNumber("0.17.1"))
        assertEquals("0.17.1", RuVoiceInstaller.versionNumber("0.17.1-lite"))
        assertEquals("0.17.1", RuVoiceInstaller.versionNumber("v0.17.1-debug"))
        assertNull(RuVoiceInstaller.versionNumber("nightly"))
        assertNull(RuVoiceInstaller.versionNumber(null))
    }

    @Test
    fun aNewerReleaseIsAnUpdateAndOnlyThat() {
        val newer = RuVoiceInstaller.judgeUpdate("0.16.9", latest("0.17.1"))
        assertEquals(RuVoiceUpdateState.Stage.AVAILABLE, newer.stage)
        assertEquals("0.17.1", newer.latest)
        assertEquals(242_925_646L, newer.size)
        assertEquals("0.16.9", newer.installed)
        assertFalse(newer.lite)
        // Numbers, not text: 0.16.10 is newer than 0.16.9.
        assertEquals(RuVoiceUpdateState.Stage.AVAILABLE, RuVoiceInstaller.judgeUpdate("0.16.9", latest("0.16.10")).stage)
        assertEquals(RuVoiceUpdateState.Stage.UP_TO_DATE, RuVoiceInstaller.judgeUpdate("0.17.1", latest("0.17.1")).stage)
        // A build newer than the releases (a test build) is not old.
        assertEquals(RuVoiceUpdateState.Stage.UP_TO_DATE, RuVoiceInstaller.judgeUpdate("0.18.0", latest("0.17.1")).stage)
        // A version that cannot be read is not called old.
        assertEquals(RuVoiceUpdateState.Stage.UP_TO_DATE, RuVoiceInstaller.judgeUpdate("nightly", latest("0.17.1")).stage)
        assertEquals(RuVoiceUpdateState.Stage.UP_TO_DATE, RuVoiceInstaller.judgeUpdate(null, latest("0.17.1")).stage)
    }

    @Test
    fun theLightBuildIsRecognisedAndComparedByItsNumber() {
        val old = RuVoiceInstaller.judgeUpdate("0.16.2-lite", latest("0.17.1"))
        assertEquals(RuVoiceUpdateState.Stage.AVAILABLE, old.stage)
        assertTrue(old.lite)
        val current = RuVoiceInstaller.judgeUpdate("0.17.1-lite", latest("0.17.1"))
        assertEquals(RuVoiceUpdateState.Stage.UP_TO_DATE, current.stage)
        assertTrue(current.lite)
    }

    @Test
    fun theReleasesAreAskedAboutOnlyWhenItIsWorthIt() {
        val hour = 60L * 60 * 1000
        val known = RuVoiceUpdateState(RuVoiceUpdateState.Stage.UP_TO_DATE, installed = "0.17.1", latest = "0.17.1")
        // Not installed: nothing to update, whatever the reader asks.
        assertFalse(RuVoiceInstaller.updateCheckDue(RuVoiceUpdateState(), null, null, false, force = true))
        // Never asked, or the reader pressed the button.
        assertTrue(RuVoiceInstaller.updateCheckDue(RuVoiceUpdateState(), "0.17.1", null, false, force = false))
        assertTrue(RuVoiceInstaller.updateCheckDue(known, "0.17.1", 1_000, false, force = true))
        // Opening the settings again soon after a look does not ask GitHub again...
        assertFalse(RuVoiceInstaller.updateCheckDue(known, "0.17.1", hour, false, force = false))
        assertTrue(RuVoiceInstaller.updateCheckDue(known, "0.17.1", 7 * hour, false, force = false))
        // ...unless the look failed (a short wait) or RuVoice is another version now.
        assertFalse(RuVoiceInstaller.updateCheckDue(known, "0.17.1", 30_000, true, force = false))
        assertTrue(RuVoiceInstaller.updateCheckDue(known, "0.17.1", 3 * 60_000, true, force = false))
        assertTrue(RuVoiceInstaller.updateCheckDue(known, "0.18.0", 1_000, false, force = false))
    }

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(RuVoiceInstaller.compareVersions("0.16.10", "0.16.9") > 0)
        assertTrue(RuVoiceInstaller.compareVersions("1.0", "0.99.99") > 0)
        assertEquals(0, RuVoiceInstaller.compareVersions("0.16", "0.16.0"))
        assertTrue(RuVoiceInstaller.compareVersions("0.16.2", "0.17") < 0)
    }
}
