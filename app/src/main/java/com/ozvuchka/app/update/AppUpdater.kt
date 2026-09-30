package com.ozvuchka.app.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import com.ozvuchka.app.speech.downloadResumable
import com.ozvuchka.app.speech.sha256Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** The newest published build of the app: `ozvuchka-<versionCode>.apk` in the latest GitHub release. */
internal data class AppRelease(val versionCode: Long, val url: String, val size: Long, val sha256: String?)

/** Where updating the app from inside it stands. */
data class AppUpdateState(
    val stage: Stage = Stage.UNKNOWN,
    /** `versionCode` of the app on the phone. */
    val installed: Long = 0,
    /** `versionCode` of the newest release; 0 until the releases were looked at. */
    val latest: Long = 0,
    val size: Long = 0,
    val completed: Long = 0,
    val total: Long = 0,
    /** Why the last look or install stopped. */
    val message: String? = null,
) {
    enum class Stage { UNKNOWN, CHECKING, UP_TO_DATE, AVAILABLE, CHECK_FAILED, DOWNLOADING, PREPARING, CONFIRMING, INSTALL_FAILED }

    /** Downloading or installing: the offer stays on screen and a second tap does nothing. */
    val installing: Boolean get() = stage == Stage.DOWNLOADING || stage == Stage.PREPARING || stage == Stage.CONFIRMING

    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

private class UpdateCancelled : IOException("cancelled")

/** A failure worded for the reader. */
private class UpdateProblem(message: String) : Exception(message)

/**
 * Updates PupsChitalka without the browser: every merge into main is published as a GitHub release
 * with the APK signed by the app's own key (see .github/workflows/release.yml), and a build's
 * `versionCode` is the second it was built, so a larger number is a newer build. The app looks at the
 * latest release now and then, offers the newer build, downloads it (resuming after a dropped
 * connection), checks that it is this app signed with the same key and hands it to Android's package
 * installer, which puts it over the installed one with the books and settings kept.
 *
 * The first time Android asks to allow installs from PupsChitalka and to confirm; from Android 12 on,
 * once the app has updated itself, later updates usually go without asking. Either way Android closes
 * the app to replace it, and [start] tells the reader about the update on the next launch.
 */
object AppUpdater {
    const val RELEASES_PAGE = "https://github.com/PupsXD/PupsChitalka/releases/latest"
    private const val LATEST_API = "https://api.github.com/repos/PupsXD/PupsChitalka/releases/latest"

    /** The APK is about 110 MB; anything far larger is not the app. */
    private const val MAX_APK_BYTES = 400L * 1024 * 1024
    private val APK = Regex("ozvuchka-(\\d+)\\.apk")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** `versionCode` counts seconds from this moment, see app/build.gradle.kts. */
    private const val VERSION_EPOCH = 1_767_225_600L

    private const val CHECK_FRESH_MS = 6L * 60 * 60 * 1000
    private const val CHECK_RETRY_MS = 2L * 60 * 1000

    private const val PREFS = "app_update"
    private const val KEY_CHECKED_AT = "checkedAt"
    private const val KEY_LATEST = "latest"
    private const val KEY_SIZE = "size"
    private const val KEY_DISMISSED = "dismissed"
    private const val KEY_INSTALLING = "installing"

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-update").apply { isDaemon = true }
    }
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state: StateFlow<AppUpdateState> = mutableState
    private val mutableConfirmation = MutableStateFlow<Intent?>(null)

    /** Android's confirmation screen for the update, waiting for the visible activity to show it. */
    val confirmation: StateFlow<Intent?> = mutableConfirmation
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    @Volatile private var cancelled = false
    /** When the last look at the releases failed; a failure is retried sooner than a success. */
    @Volatile private var failedAt = 0L
    @Volatile private var started = false

    /**
     * Picks up what an earlier launch learned about the releases, so a known update is offered at
     * once. Returns the `versionCode` the app was just updated to from inside itself, or null.
     */
    fun start(context: Context): Long? {
        val app = context.applicationContext
        val installed = installedVersion(app)
        val prefs = prefs(app)
        val installing = prefs.getLong(KEY_INSTALLING, 0)
        var updated: Long? = null
        if (installing > 0 && installed >= installing) {
            updated = installed
            prefs.edit().remove(KEY_INSTALLING).apply()
            downloads(app).deleteRecursively()
        }
        if (!started) {
            started = true
            val latest = prefs.getLong(KEY_LATEST, 0)
            mutableState.value = when {
                latest > installed -> AppUpdateState(AppUpdateState.Stage.AVAILABLE, installed, latest, prefs.getLong(KEY_SIZE, 0))
                latest > 0 -> AppUpdateState(AppUpdateState.Stage.UP_TO_DATE, installed, latest)
                else -> AppUpdateState(installed = installed)
            }
        }
        return updated
    }

    /**
     * Looks at the latest release. Asked on its own ([force], from the menu) it says when it cannot
     * reach GitHub; asked in passing, when the app comes to the front, it stays quiet about that and
     * asks again only after a while.
     */
    fun checkForUpdate(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        val current = mutableState.value
        if (current.installing || current.stage == AppUpdateState.Stage.CHECKING) return
        val installed = installedVersion(app)
        val checkedAt = prefs(app).getLong(KEY_CHECKED_AT, 0)
        val last = maxOf(checkedAt, failedAt)
        val age = if (last == 0L) null else System.currentTimeMillis() - last
        if (!checkDue(age, failedAt > checkedAt, force)) return
        cancelled = false
        mutableState.value = current.copy(stage = AppUpdateState.Stage.CHECKING, installed = installed, message = null)
        worker.execute {
            var failed = false
            try {
                val release = findRelease()
                remember(app, release)
                mutableState.value = judge(installed, release)
            } catch (error: Exception) {
                failed = true
                mutableState.value = if (force) {
                    current.copy(stage = AppUpdateState.Stage.CHECK_FAILED, installed = installed, message = describe(error, checking = true))
                } else {
                    // Offline in passing: whatever was known before stands.
                    current.copy(stage = if (current.stage == AppUpdateState.Stage.CHECKING) AppUpdateState.Stage.UNKNOWN else current.stage)
                }
            } finally {
                if (failed) failedAt = System.currentTimeMillis() else prefs(app).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
                activeConnection.set(null)
            }
        }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        if (mutableState.value.installing) return
        cancelled = false
        val installed = installedVersion(app)
        mutableState.value = mutableState.value.copy(stage = AppUpdateState.Stage.DOWNLOADING, installed = installed, completed = 0, total = 0, message = null)
        worker.execute {
            try {
                // Looked up again: a release may have been replaced since the last look.
                val release = findRelease().also { remember(app, it) }
                if (release.versionCode <= installed) {
                    mutableState.value = judge(installed, release)
                    return@execute
                }
                val apk = download(app, release, installed)
                mutableState.value = mutableState.value.copy(stage = AppUpdateState.Stage.PREPARING)
                verify(app, apk, release)
                commit(app, apk, release)
            } catch (error: Exception) {
                val known = mutableState.value
                mutableState.value = if (cancelled || error is UpdateCancelled) {
                    known.copy(stage = AppUpdateState.Stage.AVAILABLE, completed = 0, total = 0, message = null)
                } else {
                    known.copy(stage = AppUpdateState.Stage.INSTALL_FAILED, message = describe(error))
                }
            } finally {
                activeConnection.set(null)
            }
        }
    }

    /** Stops downloading; Android's own confirmation screen is the reader's to close. */
    fun cancel() {
        if (mutableState.value.stage == AppUpdateState.Stage.CONFIRMING) return
        cancelled = true
        activeConnection.get()?.disconnect()
    }

    /** «Не сейчас»: the offer of this build is put off until a newer one comes out. */
    fun dismiss(context: Context) {
        prefs(context.applicationContext).edit().putLong(KEY_DISMISSED, mutableState.value.latest).apply()
    }

    /** The newest release the reader put off, or 0. */
    fun dismissed(context: Context): Long = prefs(context.applicationContext).getLong(KEY_DISMISSED, 0)

    /** The confirmation screen, once: the activity that takes it shows it. */
    fun takeConfirmation(): Intent? = mutableConfirmation.getAndUpdate { null }

    /** `versionCode` of this app on the phone. */
    fun installedVersion(context: Context): Long = runCatching { ownInfo(context, 0).longVersionCode }.getOrDefault(0)

    /** «30 сентября 2026, 18:14»: when the build with this `versionCode` was made, in the phone's time. */
    fun versionDate(versionCode: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", Locale.forLanguageTag("ru"))
            .format(Instant.ofEpochSecond(VERSION_EPOCH + versionCode).atZone(zone))

    internal fun onInstallResult(context: Context, intent: Intent) {
        val known = mutableState.value
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                mutableState.value = if (confirm != null) {
                    mutableConfirmation.value = confirm
                    known.copy(stage = AppUpdateState.Stage.CONFIRMING)
                } else {
                    known.copy(stage = AppUpdateState.Stage.INSTALL_FAILED, message = "Android не показал окно установки.")
                }
            }
            // Android closes the app to replace it, so this is rarely heard; the next launch says it.
            PackageInstaller.STATUS_SUCCESS -> mutableState.value = known.copy(stage = AppUpdateState.Stage.UP_TO_DATE)
            PackageInstaller.STATUS_FAILURE_ABORTED -> {
                prefs(context).edit().remove(KEY_INSTALLING).apply()
                mutableState.value = known.copy(stage = AppUpdateState.Stage.AVAILABLE, message = "Обновление отменено. Скачанный файл сохранён — установка начнётся сразу.")
            }
            else -> {
                prefs(context).edit().remove(KEY_INSTALLING).apply()
                if (status == PackageInstaller.STATUS_FAILURE_INVALID || status == PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    downloads(context).deleteRecursively()
                }
                mutableState.value = known.copy(
                    stage = AppUpdateState.Stage.INSTALL_FAILED,
                    message = installFailure(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                )
            }
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun downloads(context: Context) = File(context.cacheDir, "app-update")

    private fun checkCancelled() {
        if (cancelled) throw UpdateCancelled()
    }

    private fun remember(context: Context, release: AppRelease) {
        prefs(context).edit()
            .putLong(KEY_LATEST, release.versionCode)
            .putLong(KEY_SIZE, release.size)
            .apply()
    }

    private fun findRelease(): AppRelease {
        checkCancelled()
        val connection = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
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
            if (code == 403 || code == 429) throw UpdateProblem("GitHub временно ограничил запросы. Попробуйте позже.")
            if (code != HttpURLConnection.HTTP_OK) throw UpdateProblem("GitHub ответил HTTP $code. Попробуйте позже.")
            val body = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            return pickAppApk(body) ?: throw UpdateProblem("В последнем релизе нет файла приложения. Попробуйте позже.")
        } catch (error: IOException) {
            checkCancelled()
            throw error
        } finally {
            connection.disconnect()
            activeConnection.set(null)
        }
    }

    private fun download(context: Context, release: AppRelease, installed: Long): File {
        val directory = downloads(context)
        check(directory.isDirectory || directory.mkdirs()) { "не удалось создать папку для загрузки" }
        val name = "ozvuchka-${release.versionCode}.apk"
        // Files of another version are of no use any more.
        directory.listFiles()?.filterNot { it.name == name || it.name == "$name.part" }?.forEach { it.delete() }
        val apk = File(directory, name)
        if (apk.isFile && (release.size <= 0 || apk.length() == release.size)) return apk
        apk.delete()
        val partial = File(directory, "$name.part")
        mutableState.value = AppUpdateState(AppUpdateState.Stage.DOWNLOADING, installed, release.versionCode, release.size, partial.length(), release.size)
        downloadResumable(release.url, partial, MAX_APK_BYTES, activeConnection, ::checkCancelled) { bytes, total ->
            mutableState.value = AppUpdateState(
                AppUpdateState.Stage.DOWNLOADING,
                installed,
                release.versionCode,
                release.size,
                bytes,
                if (release.size > 0) release.size else total,
            )
        }
        checkCancelled()
        check(partial.renameTo(apk)) { "не удалось сохранить файл" }
        return apk
    }

    private fun verify(context: Context, apk: File, release: AppRelease) {
        if (release.size > 0 && apk.length() != release.size) {
            apk.delete()
            throw UpdateProblem("Обновление скачалось не полностью. Попробуйте ещё раз.")
        }
        if (release.sha256 != null && sha256Hex(apk) != release.sha256) {
            apk.delete()
            throw UpdateProblem("Файл обновления повредился при загрузке. Попробуйте ещё раз.")
        }
        checkCancelled()
        val archive = archiveInfo(context, apk)
        if (archive?.packageName != context.packageName || archive.longVersionCode != release.versionCode) {
            apk.delete()
            throw UpdateProblem("Скачанный файл — не эта версия PupsChitalka. Попробуйте позже.")
        }
        // Android would refuse it anyway; saying why here spares the reader a cryptic error.
        val theirs = signers(archive)
        val ours = signers(ownInfo(context, PackageManager.GET_SIGNING_CERTIFICATES))
        if (theirs.isNotEmpty() && ours.isNotEmpty() && theirs != ours) {
            apk.delete()
            throw UpdateProblem(
                "Эта сборка PupsChitalka подписана другим ключом, чем новая версия, поэтому поверх неё обновление не встанет. " +
                    "Установите новую версию со страницы релизов, удалив эту один раз (книги при этом удалятся).",
            )
        }
    }

    /** SHA-256 of each certificate the app is signed with. */
    private fun signers(info: PackageInfo?): Set<String> {
        val signing = info?.signingInfo ?: return emptySet()
        val signatures = if (signing.hasMultipleSigners()) signing.apkContentsSigners else signing.signingCertificateHistory
        return signatures.orEmpty().mapTo(HashSet()) { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    private fun ownInfo(context: Context, flags: Int): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, flags)
        }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }

    private fun commit(context: Context, apk: File, release: AppRelease) {
        val installer = context.packageManager.packageInstaller
        // A session left by an interrupted attempt would only hold space.
        installer.mySessions.filter { it.appPackageName == context.packageName }
            .forEach { runCatching { installer.abandonSession(it.sessionId) } }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            // An app that updates itself need not ask each time; Android still asks when it must.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("ozvuchka.apk", 0, apk.length()).use { output ->
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
                val result = Intent(context, AppUpdateReceiver::class.java).setPackage(context.packageName)
                val pending = PendingIntent.getBroadcast(
                    context, sessionId, result, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                // The next launch tells the reader the update went in; set before Android closes the app.
                prefs(context).edit().putLong(KEY_INSTALLING, release.versionCode).commit()
                mutableState.value = mutableState.value.copy(stage = AppUpdateState.Stage.CONFIRMING)
                session.commit(pending.intentSender)
            }
        } catch (error: Exception) {
            runCatching { installer.abandonSession(sessionId) }
            prefs(context).edit().remove(KEY_INSTALLING).apply()
            throw error
        }
    }

    private fun describe(error: Exception, checking: Boolean = false): String = when (error) {
        is UpdateProblem -> error.message.orEmpty()
        is UnknownHostException, is ConnectException, is SocketTimeoutException ->
            "Нет связи с GitHub. Проверьте интернет и попробуйте ещё раз."
        else -> (if (checking) "Не удалось проверить обновления" else "Не удалось скачать обновление") +
            ": ${error.message ?: error.javaClass.simpleName}"
    }

    private fun installFailure(status: Int, detail: String?): String = when (status) {
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "Android не дал установить обновление. Разрешите PupsChitalka устанавливать приложения; на Samsung также " +
                "проверьте, что выключена «Автоблокировка» (Auto Blocker) в разделе «Безопасность и конфиденциальность»."
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "Новая версия не встаёт поверх этой: у них разные подписи. Установите её со страницы релизов."
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Новая версия не подходит для этого телефона."
        PackageInstaller.STATUS_FAILURE_INVALID -> "Файл обновления повреждён. Попробуйте ещё раз."
        PackageInstaller.STATUS_FAILURE_STORAGE -> "Не хватает места для обновления."
        else -> "Не удалось установить обновление" + (detail?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ".")
    }

    /** The app's APK in the latest release; null when the release has none. */
    internal fun pickAppApk(json: String): AppRelease? {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val assets = release.optJSONArray("assets") ?: return null
        var best: AppRelease? = null
        for (index in 0 until assets.length()) {
            val asset = assets.optJSONObject(index) ?: continue
            val code = APK.matchEntire(asset.optString("name"))?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val url = asset.optString("browser_download_url").takeIf { it.startsWith("https://") } ?: continue
            val digest = asset.optString("digest").removePrefix("sha256:").lowercase().takeIf { SHA256.matches(it) }
            if (best == null || code > best.versionCode) best = AppRelease(code, url, asset.optLong("size"), digest)
        }
        return best
    }

    /** A newer build is an update; one built after the latest release (a test build) is not old. */
    internal fun judge(installed: Long, latest: AppRelease): AppUpdateState = AppUpdateState(
        stage = if (latest.versionCode > installed) AppUpdateState.Stage.AVAILABLE else AppUpdateState.Stage.UP_TO_DATE,
        installed = installed,
        latest = latest.versionCode,
        size = latest.size,
    )

    /**
     * Whether a look at the releases is worth making: a recent one ([ageMs] since the last, across
     * launches) still stands, unless the reader asked ([force]) or the last look failed.
     */
    internal fun checkDue(ageMs: Long?, lastFailed: Boolean, force: Boolean): Boolean = when {
        force || ageMs == null || ageMs < 0 -> true
        else -> ageMs >= if (lastFailed) CHECK_RETRY_MS else CHECK_FRESH_MS
    }
}

/** Receives Android's answers about installing the update. */
class AppUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = AppUpdater.onInstallResult(context, intent)
}
