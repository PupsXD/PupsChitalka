package com.ozvuchka.app.speech

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
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
    /** In a finished install: the version this one replaced; null when RuVoice was not there before. */
    val previousVersion: String? = null,
) {
    enum class Stage { IDLE, LOOKING, DOWNLOADING, PREPARING, CONFIRMING, DONE, FAILED }

    val busy: Boolean
        get() = stage == Stage.LOOKING || stage == Stage.DOWNLOADING || stage == Stage.PREPARING || stage == Stage.CONFIRMING

    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

/** What is known about a newer RuVoice than the one on the phone. */
data class RuVoiceUpdateState(
    val stage: Stage = Stage.UNKNOWN,
    /** `versionName` of the installed RuVoice, such as «0.17.1» or «0.17.1-lite». */
    val installed: String? = null,
    /** The newest full build in the releases. */
    val latest: String? = null,
    val size: Long = 0,
    /** The installed RuVoice is the «lite» build: the full one the app downloads would replace it. */
    val lite: Boolean = false,
    val message: String? = null,
) {
    enum class Stage { UNKNOWN, CHECKING, UP_TO_DATE, AVAILABLE, FAILED }
}

private class RuVoiceCancelled : IOException("cancelled")

/** A failure worded for the reader. */
private class RuVoiceProblem(message: String) : Exception(message)

/**
 * Gets RuVoice without leaving the reader: finds the newest full build in the engine's GitHub
 * releases, downloads it (resuming after a dropped connection), checks it and hands it to Android's
 * package installer. Android always asks the user to confirm installing another app, and the first
 * time also to allow installs from this one; RuVoice's code has no license to ship it inside this APK.
 *
 * The same route updates an installed RuVoice: [checkForUpdate] compares its version with the newest
 * release, and [install] puts the newer build over it.
 */
object RuVoiceInstaller {
    private const val RELEASES_API = "https://api.github.com/repos/kost-t-human/ruvoice-tts/releases?per_page=20"

    /** The full build is about 230 MB; anything far larger is not the engine. */
    private const val MAX_APK_BYTES = 700L * 1024 * 1024
    private val FULL_APK = Regex("ruvoice-tts-(\\d+(?:\\.\\d+)*)\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")
    private val VERSION_NUMBER = Regex("\\d+(?:\\.\\d+)*")

    /** How long a look at the releases stays good, so opening the settings again does not ask GitHub again. */
    private const val CHECK_FRESH_MS = 6L * 60 * 60 * 1000
    private const val CHECK_RETRY_MS = 2L * 60 * 1000

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-ruvoice").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(RuVoiceInstallState())
    val state: StateFlow<RuVoiceInstallState> = mutableState
    private val mutableConfirmation = MutableStateFlow<Intent?>(null)

    /** Android's confirmation screen for the install, waiting for the visible activity to show it. */
    val confirmation: StateFlow<Intent?> = mutableConfirmation
    private val mutableUpdate = MutableStateFlow(RuVoiceUpdateState())

    /** Whether the installed RuVoice is the newest, as far as the last look at the releases showed. */
    val update: StateFlow<RuVoiceUpdateState> = mutableUpdate
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    @Volatile private var cancelled = false

    /** The version an install in progress replaces; kept here because Android's answer arrives later. */
    @Volatile private var replacedVersion: String? = null
    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastCheckFailed = false

    fun install(context: Context) {
        val app = context.applicationContext
        if (state.value.busy) return
        cancelled = false
        replacedVersion = installedVersion(app)
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

    /**
     * Looks at the releases and tells whether they hold a newer RuVoice than the installed one. Asked
     * on its own ([force], from a button) it says when it cannot reach GitHub; asked in passing, when
     * the settings open, it stays quiet about that and asks again only after a while.
     */
    fun checkForUpdate(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val previous = mutableUpdate.value
        if (state.value.busy || previous.stage == RuVoiceUpdateState.Stage.CHECKING) return
        val installed = installedVersion(app)
        if (installed == null) {
            mutableUpdate.value = RuVoiceUpdateState()
            return
        }
        val age = if (lastCheckAt == 0L) null else SystemClock.elapsedRealtime() - lastCheckAt
        if (!updateCheckDue(previous, installed, age, lastCheckFailed, force)) return
        cancelled = false
        mutableUpdate.value = RuVoiceUpdateState(RuVoiceUpdateState.Stage.CHECKING, installed = installed)
        worker.execute {
            var failed = false
            try {
                mutableUpdate.value = judgeUpdate(installed, findRelease())
            } catch (_: RuVoiceCancelled) {
                mutableUpdate.value = RuVoiceUpdateState(installed = installed)
            } catch (error: Exception) {
                failed = true
                mutableUpdate.value = if (force) {
                    RuVoiceUpdateState(RuVoiceUpdateState.Stage.FAILED, installed = installed, message = describe(error, checking = true))
                } else {
                    previous.takeIf { it.installed == installed && it.stage != RuVoiceUpdateState.Stage.CHECKING }
                        ?: RuVoiceUpdateState(installed = installed)
                }
            } finally {
                lastCheckFailed = failed
                lastCheckAt = SystemClock.elapsedRealtime()
                activeConnection.set(null)
            }
        }
    }

    /** `versionName` of the RuVoice on the phone, or null when it is not installed. */
    internal fun installedVersion(context: Context): String? = runCatching {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(VoiceCatalog.RUVOICE_PACKAGE, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(VoiceCatalog.RUVOICE_PACKAGE, 0)
        }
        info.versionName
    }.getOrNull()

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
        if (state.value.stage == RuVoiceInstallState.Stage.DONE) {
            mutableState.value = RuVoiceInstallState()
            replacedVersion = null
        }
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
                // An update restarts RuVoice's process: the connection kept to the old one is dead.
                SynthesisHub.shared(context).forgetSystemEngine(VoiceCatalog.RUVOICE_PACKAGE)
                if (version != null) {
                    mutableUpdate.value = RuVoiceUpdateState(RuVoiceUpdateState.Stage.UP_TO_DATE, installed = version, latest = version)
                    lastCheckAt = SystemClock.elapsedRealtime()
                    lastCheckFailed = false
                }
                mutableState.value = RuVoiceInstallState(RuVoiceInstallState.Stage.DONE, version, previousVersion = replacedVersion)
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

    private fun describe(error: Exception, checking: Boolean = false): String = when (error) {
        is RuVoiceProblem -> error.message.orEmpty()
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз."
        else -> (if (checking) "Не удалось проверить обновления" else "Не удалось скачать RuVoice") +
            ": ${error.message ?: error.javaClass.simpleName}"
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

    /** The number in a `versionName`: «0.17.1» from «0.17.1» and from «0.17.1-lite»; null when there is none. */
    internal fun versionNumber(versionName: String?): String? = VERSION_NUMBER.find(versionName.orEmpty())?.value

    /** Whether the [latest] full build is newer than the RuVoice [installed]; a version that cannot be read is not called old. */
    internal fun judgeUpdate(installed: String?, latest: RuVoiceRelease): RuVoiceUpdateState {
        val number = versionNumber(installed)
        val newer = number != null && compareVersions(latest.version, number) > 0
        return RuVoiceUpdateState(
            stage = if (newer) RuVoiceUpdateState.Stage.AVAILABLE else RuVoiceUpdateState.Stage.UP_TO_DATE,
            installed = installed,
            latest = latest.version,
            size = latest.size,
            lite = installed?.contains("lite", ignoreCase = true) == true,
        )
    }

    /**
     * Whether a look at the releases is worth making: a recent one ([ageMs] since the last) still
     * stands, unless it failed, RuVoice changed since, or the reader asked ([force]).
     */
    internal fun updateCheckDue(previous: RuVoiceUpdateState, installed: String?, ageMs: Long?, lastFailed: Boolean, force: Boolean): Boolean = when {
        installed == null -> false
        force || ageMs == null || previous.installed != installed -> true
        else -> ageMs >= if (lastFailed) CHECK_RETRY_MS else CHECK_FRESH_MS
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
