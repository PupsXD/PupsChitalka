package com.ozvuchka.app.speech

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** The newest full RuVoice build: the APK with the Silero model inside, which reads right after install. */
internal data class RuVoiceRelease(val version: String, val url: String, val size: Long, val sha256: String?)

/** Where getting RuVoice from inside the app stands. */
data class RuVoiceInstallState(
    val stage: Stage = Stage.IDLE,
    val version: String? = null,
    val completed: Long = 0,
    val total: Long = 0,
    /** Why the last attempt stopped: a cancel, a refusal or an error. */
    val message: String? = null,
) {
    enum class Stage { IDLE, LOOKING, DOWNLOADING, PREPARING, CONFIRMING, DONE, FAILED }

    val busy: Boolean
        get() = stage == Stage.LOOKING || stage == Stage.DOWNLOADING || stage == Stage.PREPARING || stage == Stage.CONFIRMING

    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

private class RuVoiceCancelled : IOException("cancelled")

/** A failure worded for the reader. */
private class RuVoiceProblem(message: String) : Exception(message)

/**
 * Gets RuVoice without leaving the reader: finds the newest full build in the engine's GitHub
 * releases, downloads it (resuming after a dropped connection), checks it and hands it to Android's
 * package installer. Android always asks the user to confirm installing another app, and the first
 * time also to allow installs from this one; RuVoice's code has no license to ship it inside this APK.
 */
object RuVoiceInstaller {
    private const val RELEASES_API = "https://api.github.com/repos/kost-t-human/ruvoice-tts/releases?per_page=20"

    /** The full build is about 230 MB; anything far larger is not the engine. */
    private const val MAX_APK_BYTES = 700L * 1024 * 1024
    private val FULL_APK = Regex("ruvoice-tts-(\\d+(?:\\.\\d+)*)\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-ruvoice").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(RuVoiceInstallState())
    val state: StateFlow<RuVoiceInstallState> = mutableState
    private val mutableConfirmation = MutableStateFlow<Intent?>(null)

    /** Android's confirmation screen for the install, waiting for the visible activity to show it. */
    val confirmation: StateFlow<Intent?> = mutableConfirmation
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    @Volatile private var cancelled = false

    fun install(context: Context) {
        val app = context.applicationContext
        if (state.value.busy) return
        cancelled = false
        mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.LOOKING)
        worker.execute {
            try {
                val release = findRelease()
                val apk = download(app, release)
                mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.PREPARING, release.version)
                verify(app, apk, release)
                commit(app, apk, release.version)
            } catch (_: RuVoiceCancelled) {
                mutableState.value = RuVoiceInstallState(message = "Загрузка RuVoice отменена")
            } catch (error: Exception) {
                mutableState.value = when {
                    cancelled -> RuVoiceInstallState(message = "Загрузка RuVoice отменена")
                    else -> RuVoiceInstallState(RuVoiceInstallState.Stage.FAILED, message = describe(error))
                }
            } finally {
                activeConnection.set(null)
            }
        }
    }

    /** Stops looking up or downloading; Android's own confirmation screen is the user's to close. */
    fun cancel() {
        if (state.value.stage == RuVoiceInstallState.Stage.CONFIRMING) return
        cancelled = true
        activeConnection.get()?.disconnect()
    }

    /** The confirmation screen, once: the activity that takes it shows it. */
    fun takeConfirmation(): Intent? = mutableConfirmation.getAndUpdate { null }

    /** The app noticed the finished install; the offer goes back to rest. */
    fun acknowledge() {
        if (state.value.stage == RuVoiceInstallState.Stage.DONE) mutableState.value = RuVoiceInstallState()
    }

    internal fun onInstallResult(context: Context, intent: Intent) {
        val version = state.value.version
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    mutableConfirmation.value = confirm
                    mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.CONFIRMING, version)
                } else {
                    mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.FAILED, version, message = "Android не показал окно установки RuVoice.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                downloads(context).deleteRecursively()
                mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.DONE, version)
            }
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                mutableState.value = RuVoiceInstallState(version = version, message = "Установка RuVoice отменена")
            }
            else -> {
                if (status == PackageInstaller.STATUS_FAILURE_INVALID) downloads(context).deleteRecursively()
                mutableState.value = RuVoiceInstallState(
                    RuVoiceInstallState.Stage.FAILED,
                    version,
                    message = installFailure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                )
            }
        }
    }

    private fun checkCancelled() {
        if (cancelled) throw RuVoiceCancelled()
    }

    private fun downloads(context: Context) = File(context.cacheDir, "ruvoice")

    private fun findRelease(): RuVoiceRelease {
        checkCancelled()
        val connection = (URL(RELEASES_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "Ozvuchka-Android/2.0")
        }
        activeConnection.set(connection)
        try {
            val code = connection.responseCode
            checkCancelled()
            if (code == 403 || code == 429) {
                throw RuVoiceProblem("GitHub временно ограничил запросы. Попробуйте позже или скачайте RuVoice со страницы релизов.")
            }
            if (code != HttpURLConnection.HTTP_OK) {
                throw RuVoiceProblem("GitHub ответил HTTP $code. Попробуйте позже или скачайте RuVoice со страницы релизов.")
            }
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return pickRuVoiceApk(body)
                ?: throw RuVoiceProblem("В релизах RuVoice нет полной сборки. Скачайте её со страницы релизов.")
        } catch (error: IOException) {
            checkCancelled()
            throw error
        } finally {
            connection.disconnect()
            activeConnection.set(null)
        }
    }

    private fun download(context: Context, release: RuVoiceRelease): File {
        val directory = downloads(context)
        check(directory.isDirectory || directory.mkdirs()) { "не удалось создать папку для загрузки" }
        val name = "ruvoice-tts-${release.version}.apk"
        // Files of another version are of no use any more.
        directory.listFiles()?.filterNot { it.name == name || it.name == "$name.part" }?.forEach { it.delete() }
        val apk = File(directory, name)
        if (apk.isFile && (release.size <= 0 || apk.length() == release.size)) return apk
        apk.delete()
        val partial = File(directory, "$name.part")
        mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.DOWNLOADING, release.version, partial.length(), release.size)
        downloadResumable(release.url, partial, MAX_APK_BYTES, activeConnection, ::checkCancelled) { bytes, total ->
            mutableState.value = RuVoiceInstallState(
                RuVoiceInstallState.Stage.DOWNLOADING,
                release.version,
                bytes,
                if (release.size > 0) release.size else total,
            )
        }
        checkCancelled()
        check(partial.renameTo(apk)) { "не удалось сохранить файл" }
        return apk
    }

    private fun verify(context: Context, apk: File, release: RuVoiceRelease) {
        if (release.size > 0 && apk.length() != release.size) {
            apk.delete()
            throw RuVoiceProblem("RuVoice скачался не полностью. Попробуйте ещё раз.")
        }
        if (release.sha256 != null && sha256Hex(apk) != release.sha256) {
            apk.delete()
            throw RuVoiceProblem("Файл RuVoice повредился при загрузке. Попробуйте ещё раз.")
        }
        checkCancelled()
        if (archiveInfo(context, apk)?.packageName != VoiceCatalog.RUVOICE_PACKAGE) {
            apk.delete()
            throw RuVoiceProblem("Скачанный файл — не RuVoice. Скачайте его со страницы релизов.")
        }
    }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.path, 0)
        }

    private fun commit(context: Context, apk: File, version: String) {
        val installer = context.packageManager.packageInstaller
        // A session left by an interrupted attempt would only hold space.
        installer.mySessions.filter { it.appPackageName == VoiceCatalog.RUVOICE_PACKAGE }
            .forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(VoiceCatalog.RUVOICE_PACKAGE)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("ruvoice.apk", 0, apk.length()).use { output ->
                        val buffer = ByteArray(256 * 1024)
                        while (true) {
                            checkCancelled()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        session.fsync(output)
                    }
                }
                checkCancelled()
                // The installer fills in the status, so the intent has to stay mutable.
                val result = Intent(context, RuVoiceInstallReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                // Set before committing: the installer's answer may arrive at once and must win.
                mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.CONFIRMING, version)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            throw error
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is RuVoiceProblem -> error.message.orEmpty()
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз."
        else -> "Не удалось скачать RuVoice: ${error.message ?: error.javaClass.simpleName}"
    }

    private fun installFailure(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Android не дал установить RuVoice. Разрешите PupsChitalka устанавливать приложения; на Samsung также " +
                "проверьте, что выключена «Автоблокировка» (Auto Blocker) в разделе «Безопасность и конфиденциальность»."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Уже установлен RuVoice с другой подписью. Удалите его и повторите."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "RuVoice не подходит для этого телефона."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Файл RuVoice повреждён. Попробуйте ещё раз."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для установки RuVoice."
        else -> "Не удалось установить RuVoice" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    /**
     * The newest full build among published releases: `ruvoice-tts-X.Y.Z.apk`, not the `-lite` one
     * (it needs a voice pack installed by hand) and not the voice-pack release.
     */
    internal fun pickRuVoiceApk(json: String): RuVoiceRelease? {
        val releases = JSONArray(json)
        var best: RuVoiceRelease? = null
        for (index in 0 until releases.length()) {
            val release = releases.optJSONObject(index) ?: continue
            if (release.optBoolean("draft") || release.optBoolean("prerelease")) continue
            val assets = release.optJSONArray("assets") ?: continue
            for (assetIndex in 0 until assets.length()) {
                val asset = assets.optJSONObject(assetIndex) ?: continue
                val version = FULL_APK.matchEntire(asset.optString("name"))?.groupValues?.get(1) ?: continue
                val url = asset.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
                val digest = asset.optString("digest").removePrefix("sha256:").lowercase().takeIf { SHA256.matches(it) }
                val candidate = RuVoiceRelease(version, url, asset.optLong("size"), digest)
                if (best == null || compareVersions(candidate.version, best.version) > 0) best = candidate
            }
        }
        return best
    }

    internal fun compareVersions(first: String, second: String): Int {
        val left = first.split('.').map { it.toIntOrNull() ?: 0 }
        val right = second.split('.').map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 }.compareTo(right.getOrElse(index) { 0 })
            if (difference != 0) return difference
        }
        return 0
    }
}

/** Receives Android's answers about installing RuVoice. */
class RuVoiceInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = RuVoiceInstaller.onInstallResult(context, intent)
}
