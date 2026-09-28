package com.ozvuchka.app.speech

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/** Neural voice packages that are downloaded once and then work offline. */
enum class SpeechModel(
    val directoryName: String,
    val title: String,
    val sizeLabel: String,
    internal val source: ModelSource,
    internal val requiredFiles: List<String>,
) {
    SUPERTONIC(
        directoryName = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
        title = "Supertonic 3",
        sizeLabel = "≈ 130 МБ",
        source = ModelSource.Archive(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2",
        ),
        requiredFiles = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
        ),
    ),
    SUPERTONIC_FULL(
        directoryName = "supertonic-3-full",
        title = "Supertonic 3 · полная точность",
        sizeLabel = "≈ 400 МБ",
        source = ModelSource.Files(
            baseUrl = "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx",
            names = listOf("duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx"),
            copiedFrom = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
            copiedNames = listOf("tts.json", "unicode_indexer.bin", "voice.bin"),
        ),
        requiredFiles = listOf(
            "duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx",
            "tts.json", "unicode_indexer.bin", "voice.bin",
        ),
    ),
    KOKORO(
        directoryName = "kokoro-int8-multi-lang-v1_0",
        title = "Kokoro v1.0",
        sizeLabel = "≈ 130 МБ",
        source = ModelSource.Archive(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2",
        ),
        requiredFiles = listOf("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab"),
    ),
    KOKORO_FULL(
        directoryName = "kokoro-multi-lang-v1_0",
        title = "Kokoro v1.0 · полная точность",
        sizeLabel = "≈ 350 МБ",
        source = ModelSource.Archive(
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2",
        ),
        requiredFiles = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab"),
    );

    /** Unquantized weights; the other variant of the same voice is the compact INT8 build. */
    val isFullPrecision: Boolean get() = this == SUPERTONIC_FULL || this == KOKORO_FULL

    /** The Kokoro archives also carry Chinese dictionaries that an English voice never reads. */
    internal fun keepArchiveEntry(path: String): Boolean = when (this) {
        KOKORO, KOKORO_FULL -> path.startsWith("espeak-ng-data/") ||
            path == "voices.bin" || path == "tokens.txt" || path == "LICENSE" ||
            path == "model.onnx" || path == "model.int8.onnx"
        else -> path.substringAfterLast('/') in requiredFiles
    }
}

internal sealed class ModelSource {
    class Archive(val url: String) : ModelSource()
    class Files(
        val baseUrl: String,
        val names: List<String>,
        val copiedFrom: String,
        val copiedNames: List<String>,
    ) : ModelSource()
}

data class ModelInstallState(
    val model: SpeechModel,
    val stage: Stage,
    val completed: Long = 0,
    val total: Long = 0,
    val message: String? = null,
) {
    enum class Stage { QUEUED, DOWNLOADING, EXTRACTING, DONE, CANCELLED, FAILED }

    val fraction: Float? get() = if (total > 0) (completed.toFloat() / total).coerceIn(0f, 1f) else null
}

private class InstallCancelled : IOException("cancelled")

/**
 * Installs voice models into app-private storage. Work runs on one app-scoped thread, so a
 * download survives leaving the reader; interrupted archives resume with an HTTP Range request.
 */
object SpeechModels {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "ozvuchka-model-install").apply { isDaemon = true }
    }
    private val mutableStates = MutableStateFlow<Map<SpeechModel, ModelInstallState>>(emptyMap())
    val states: StateFlow<Map<SpeechModel, ModelInstallState>> = mutableStates
    private val cancelled = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<SpeechModel, Boolean>())
    private val activeConnection = AtomicReference<HttpURLConnection?>(null)
    @Volatile private var activeModel: SpeechModel? = null

    fun root(context: Context): File = File(context.applicationContext.filesDir, "speech_models")

    fun directory(context: Context, model: SpeechModel): File = File(root(context), model.directoryName)

    fun isInstalled(context: Context, model: SpeechModel): Boolean {
        val directory = directory(context, model)
        return model.requiredFiles.all { File(directory, it).let { file -> file.isFile && file.length() > 0 } }
    }

    fun isBusy(model: SpeechModel): Boolean = states.value[model]?.stage in setOf(
        ModelInstallState.Stage.QUEUED, ModelInstallState.Stage.DOWNLOADING, ModelInstallState.Stage.EXTRACTING,
    )

    fun install(context: Context, model: SpeechModel) {
        val app = context.applicationContext
        if (isInstalled(app, model) || isBusy(model)) return
        cancelled.remove(model)
        publish(ModelInstallState(model, ModelInstallState.Stage.QUEUED))
        worker.execute {
            if (model in cancelled) {
                publish(ModelInstallState(model, ModelInstallState.Stage.CANCELLED))
                return@execute
            }
            try {
                if (model == SpeechModel.SUPERTONIC_FULL && !isInstalled(app, SpeechModel.SUPERTONIC)) {
                    installNow(app, SpeechModel.SUPERTONIC)
                }
                installNow(app, model)
                publish(ModelInstallState(model, ModelInstallState.Stage.DONE))
            } catch (_: InstallCancelled) {
                publish(ModelInstallState(model, ModelInstallState.Stage.CANCELLED, message = "Загрузка отменена"))
            } catch (error: Exception) {
                if (model in cancelled) {
                    publish(ModelInstallState(model, ModelInstallState.Stage.CANCELLED, message = "Загрузка отменена"))
                } else {
                    publish(
                        ModelInstallState(
                            model, ModelInstallState.Stage.FAILED,
                            message = "Не удалось загрузить ${model.title}: ${error.message ?: "ошибка сети"}",
                        )
                    )
                }
            } finally {
                activeConnection.set(null)
                activeModel = null
            }
        }
    }

    fun cancel(model: SpeechModel) {
        cancelled += model
        if (activeModel == model) activeConnection.get()?.disconnect()
    }

    /** Removes an installed model. Partial downloads are kept only while an install is queued. */
    fun delete(context: Context, model: SpeechModel): Boolean {
        if (isBusy(model)) return false
        val directory = directory(context, model)
        val deleted = deleteOwned(root(context), directory)
        File(root(context), "${model.directoryName}.part").delete()
        mutableStates.value = mutableStates.value - model
        return deleted
    }

    fun clearState(model: SpeechModel) {
        if (!isBusy(model)) mutableStates.value = mutableStates.value - model
    }

    private fun publish(state: ModelInstallState) {
        mutableStates.value = mutableStates.value + (state.model to state)
    }

    private fun checkCancelled(model: SpeechModel) {
        if (model in cancelled) throw InstallCancelled()
    }

    private fun installNow(context: Context, model: SpeechModel) {
        if (isInstalled(context, model)) return
        activeModel = model
        val root = root(context)
        check(root.isDirectory || root.mkdirs()) { "Не удалось создать каталог моделей" }
        val target = directory(context, model)
        val staging = File(root, "${model.directoryName}.installing")
        if (staging.exists()) check(deleteOwned(root, staging)) { "Не удалось очистить временные файлы" }
        check(staging.mkdirs()) { "Не удалось подготовить каталог модели" }
        try {
            when (val source = model.source) {
                is ModelSource.Archive -> {
                    val archive = File(root, "${model.directoryName}.part")
                    download(model, source.url, archive, maxBytes = 900L * 1024 * 1024, progressTotal = null)
                    extract(model, archive, staging)
                    archive.delete()
                }
                is ModelSource.Files -> {
                    val origin = File(root, source.copiedFrom)
                    source.copiedNames.forEach { name ->
                        File(origin, name).inputStream().use { input ->
                            File(staging, name).outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                    var completed = 0L
                    source.names.forEach { name ->
                        checkCancelled(model)
                        val file = File(staging, name)
                        completed += download(
                            model, "${source.baseUrl}/$name?download=true", file,
                            maxBytes = 400L * 1024 * 1024, progressTotal = 400_000_000L, offset = completed,
                        )
                    }
                }
            }
            checkCancelled(model)
            check(model.requiredFiles.all { File(staging, it).length() > 0 }) { "Модель загружена не полностью" }
            if (target.exists()) check(deleteOwned(root, target)) { "Не удалось заменить повреждённую модель" }
            check(staging.renameTo(target)) { "Не удалось завершить установку" }
        } finally {
            if (staging.exists()) deleteOwned(root, staging)
        }
    }

    /** Downloads one file of [model]; progress is reported relative to [offset] for multi-file models. */
    private fun download(
        model: SpeechModel,
        url: String,
        file: File,
        maxBytes: Long,
        progressTotal: Long?,
        offset: Long = 0,
    ): Long = downloadResumable(url, file, maxBytes, activeConnection, checkCancelled = { checkCancelled(model) }) { bytes, total ->
        publish(ModelInstallState(model, ModelInstallState.Stage.DOWNLOADING, offset + bytes, progressTotal ?: total))
    }

    private fun extract(model: SpeechModel, archive: File, staging: File) {
        publish(ModelInstallState(model, ModelInstallState.Stage.EXTRACTING, 0, archive.length()))
        val buffer = ByteArray(256 * 1024)
        var totalBytes = 0L
        val counting = CountingInputStream(BufferedInputStream(FileInputStream(archive), 1 shl 16))
        BZip2CompressorInputStream(counting, false).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                var lastReport = 0L
                while (true) {
                    checkCancelled(model)
                    @Suppress("DEPRECATION")
                    val entry = tar.nextTarEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
                    check(!name.startsWith('/') && !name.contains(':') && parts.none { it == ".." }) {
                        "архив содержит недопустимый путь"
                    }
                    // Every archive has one top-level directory named after the model.
                    val relative = parts.drop(1).joinToString("/")
                    if (relative.isEmpty() || entry.isDirectory) continue
                    if (!entry.isFile) continue // symlinks are skipped, never followed
                    if (!model.keepArchiveEntry(relative)) continue
                    check(entry.size in 0L..700L * 1024 * 1024) { "некорректный размер файла" }
                    totalBytes += entry.size
                    check(totalBytes <= 1_200L * 1024 * 1024) { "архив слишком велик" }
                    // Supertonic files are looked up by name only, whatever folder the archive uses.
                    val keepTree = model == SpeechModel.KOKORO || model == SpeechModel.KOKORO_FULL
                    val output = File(staging, if (keepTree) relative else relative.substringAfterLast('/'))
                    check(output.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                        "архив содержит недопустимый путь"
                    }
                    output.parentFile?.mkdirs()
                    var written = 0L
                    BufferedOutputStream(FileOutputStream(output)).use { sink ->
                        while (written < entry.size) {
                            checkCancelled(model)
                            val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - written).toInt())
                            check(count > 0) { "файл модели повреждён: $relative" }
                            sink.write(buffer, 0, count)
                            written += count
                        }
                    }
                    val now = System.currentTimeMillis()
                    if (now - lastReport > 250) {
                        lastReport = now
                        publish(ModelInstallState(model, ModelInstallState.Stage.EXTRACTING, counting.count, archive.length()))
                    }
                }
            }
        }
    }

    private fun deleteOwned(root: File, directory: File): Boolean {
        if (!directory.exists()) return true
        // Only fixed-name children of filesDir/speech_models are ever removed.
        if (directory.parentFile?.canonicalFile != root.canonicalFile) return false
        return directory.walkBottomUp().all { it.delete() || !it.exists() }
    }

    private class CountingInputStream(private val input: java.io.InputStream) : java.io.FilterInputStream(input) {
        @Volatile var count = 0L
            private set

        override fun read(): Int = super.read().also { if (it >= 0) count++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

        override fun skip(n: Long): Long = super.skip(n).also { count += it }
    }
}
