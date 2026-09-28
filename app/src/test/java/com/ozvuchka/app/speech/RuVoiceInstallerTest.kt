package com.ozvuchka.app.speech

import org.junit.Assert.assertEquals
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

    @Test
    fun versionsCompareByNumbers() {
        assertTrue(RuVoiceInstaller.compareVersions("0.16.10", "0.16.9") > 0)
        assertTrue(RuVoiceInstaller.compareVersions("1.0", "0.99.99") > 0)
        assertEquals(0, RuVoiceInstaller.compareVersions("0.16", "0.16.0"))
        assertTrue(RuVoiceInstaller.compareVersions("0.16.2", "0.17") < 0)
    }
}
