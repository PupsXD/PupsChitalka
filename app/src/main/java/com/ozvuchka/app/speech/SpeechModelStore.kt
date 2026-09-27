package com.ozvuchka.app.speech

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

internal enum class ModelInstallStage { DOWNLOAD, EXTRACT }

internal data class ModelInstallProgress(
    val stage: ModelInstallStage,
    val completed: Long,
    val total: Long,
    val currentFile: String? = null,
)

internal class ModelInstallCancelledException : Exception()

/** Downloads only the published Supertonic 3 int8 archive. No text is transmitted. */
internal class SpeechModelStore(filesDir: File) {
    private val root = File(filesDir, "speech_models")
    private val target = File(root, MODEL_NAME)
    private val staging = File(root, "$MODEL_NAME.installing")
    private val archive = File(root, "$MODEL_NAME.tar.bz2.part")
    private val fullTarget = File(root, FULL_NAME)
    private val fullStaging = File(root, "$FULL_NAME.installing")

    @Volatile private var activeConnection: HttpURLConnection? = null

    fun modelDirectory(preferFull: Boolean = true): File =
        if (preferFull && isFullInstalled()) fullTarget else target

    fun isFullInstalled(): Boolean = FULL_FILES.all { name ->
        File(fullTarget, name).let { it.isFile && it.length() > 0 }
    }

    fun isInstalled(): Boolean = REQUIRED_FILES.all { name ->
        File(target, name).let { it.isFile && it.length() > 0 }
    }

    fun interruptDownload() {
        activeConnection?.disconnect()
    }

    fun install(
        cancelled: () -> Boolean,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        if (isInstalled()) return
        check(root.isDirectory || root.mkdirs()) { "Не удалось создать каталог модели" }
        // Only our own incomplete staging/archive files are removed. An installed model is left intact.
        deleteOwnedStaging()
        try {
            download(cancelled, onProgress)
            extract(cancelled, onProgress)
            if (cancelled()) throw ModelInstallCancelledException()
            check(REQUIRED_FILES.all { File(staging, it).length() > 0 }) {
                "Архив модели неполон"
            }
            if (target.exists()) {
                // This can only be a previous, incomplete app-managed installation.
                check(!isInstalled()) { "Модель уже установлена" }
                check(deleteTree(target)) { "Не удалось удалить повреждённую модель" }
            }
            check(staging.renameTo(target)) { "Не удалось завершить установку модели" }
        } finally {
            activeConnection = null
            archive.delete()
            if (staging.exists()) deleteTree(staging)
        }
    }

    /** Keeps the working int8 package as a fallback while installing the publisher's full ONNX weights. */
    fun installFull(
        cancelled: () -> Boolean,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        if (isFullInstalled()) return
        check(isInstalled()) { "Сначала установите базовый голос" }
        check(root.isDirectory || root.mkdirs()) { "Не удалось создать каталог модели" }
        if (fullStaging.exists()) check(deleteTree(fullStaging)) { "Не удалось очистить временные файлы" }
        check(fullStaging.mkdirs()) { "Не удалось подготовить каталог модели" }
        try {
            val metadata = listOf("tts.json", "unicode_indexer.bin", "voice.bin")
            metadata.forEach { name ->
                File(target, name).inputStream().use { source ->
                    File(fullStaging, name).outputStream().use { sink -> source.copyTo(sink) }
                }
            }
            var completed = 0L
            for (name in FULL_ONNX_FILES) {
                if (cancelled()) throw ModelInstallCancelledException()
                val connection = (URL("$FULL_MODEL_BASE_URL/$name?download=true").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 30_000
                    instanceFollowRedirects = true
                    setRequestProperty("Accept-Encoding", "identity")
                    setRequestProperty("User-Agent", "Ozvuchka-Android/1.0")
                }
                activeConnection = connection
                try {
                    connection.connect()
                    check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                        "Сервер модели ответил HTTP ${connection.responseCode}"
                    }
                    check(connection.url.protocol.equals("https", ignoreCase = true)) {
                        "Небезопасная переадресация загрузки"
                    }
                    check(connection.contentLengthLong in 1..MAX_FULL_FILE_BYTES) {
                        "Некорректный размер файла $name"
                    }
                    var fileBytes = 0L
                    var lastReport = 0L
                    connection.inputStream.use { source ->
                        BufferedOutputStream(FileOutputStream(File(fullStaging, name))).use { sink ->
                            val buffer = ByteArray(128 * 1024)
                            while (true) {
                                if (cancelled()) throw ModelInstallCancelledException()
                                val count = source.read(buffer)
                                if (count < 0) break
                                sink.write(buffer, 0, count)
                                fileBytes += count
                                check(fileBytes <= MAX_FULL_FILE_BYTES && completed + fileBytes <= MAX_FULL_TOTAL_BYTES) {
                                    "Модель слишком велика"
                                }
                                val now = System.currentTimeMillis()
                                if (now - lastReport >= 250L) {
                                    onProgress(ModelInstallProgress(ModelInstallStage.DOWNLOAD, completed + fileBytes, FULL_EXPECTED_BYTES, name))
                                    lastReport = now
                                }
                            }
                        }
                    }
                    check(fileBytes == connection.contentLengthLong) { "Загрузка файла $name прервалась" }
                    completed += fileBytes
                    onProgress(ModelInstallProgress(ModelInstallStage.DOWNLOAD, completed, FULL_EXPECTED_BYTES, name))
                } catch (error: Exception) {
                    if (cancelled()) throw ModelInstallCancelledException()
                    throw error
                } finally {
                    connection.disconnect()
                    activeConnection = null
                }
            }
            check(FULL_FILES.all { File(fullStaging, it).length() > 0 }) { "Модель загружена не полностью" }
            if (cancelled()) throw ModelInstallCancelledException()
            if (fullTarget.exists()) check(deleteTree(fullTarget)) { "Не удалось заменить неполную модель" }
            check(fullStaging.renameTo(fullTarget)) { "Не удалось завершить установку модели" }
        } finally {
            activeConnection = null
            if (fullStaging.exists()) deleteTree(fullStaging)
        }
    }

    private fun download(
        cancelled: () -> Boolean,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        if (cancelled()) throw ModelInstallCancelledException()
        val connection = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Accept-Encoding", "identity")
            setRequestProperty("User-Agent", "Ozvuchka-Android/1.0")
        }
        activeConnection = connection
        try {
            connection.connect()
            check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                "Сервер модели ответил HTTP ${connection.responseCode}"
            }
            check(connection.url.protocol.equals("https", ignoreCase = true)) {
                "Небезопасная переадресация загрузки"
            }
            val total = connection.contentLengthLong.coerceAtLeast(0L)
            check(total <= MAX_ARCHIVE_BYTES) { "Архив модели слишком велик" }
            var bytes = 0L
            var lastReport = 0L
            onProgress(ModelInstallProgress(ModelInstallStage.DOWNLOAD, 0, total))
            connection.inputStream.use { source ->
                BufferedOutputStream(FileOutputStream(archive)).use { sink ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        if (cancelled()) throw ModelInstallCancelledException()
                        val count = source.read(buffer)
                        if (count < 0) break
                        sink.write(buffer, 0, count)
                        bytes += count
                        check(bytes <= MAX_ARCHIVE_BYTES) { "Архив модели слишком велик" }
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 250L) {
                            onProgress(ModelInstallProgress(ModelInstallStage.DOWNLOAD, bytes, total))
                            lastReport = now
                        }
                    }
                }
            }
            if (cancelled()) throw ModelInstallCancelledException()
            check(bytes > 0) { "Получен пустой архив модели" }
            if (total > 0) check(bytes == total) { "Загрузка модели прервалась" }
            onProgress(ModelInstallProgress(ModelInstallStage.DOWNLOAD, bytes, total))
        } catch (error: Exception) {
            if (cancelled()) throw ModelInstallCancelledException()
            throw error
        } finally {
            connection.disconnect()
            activeConnection = null
        }
    }

    private fun extract(
        cancelled: () -> Boolean,
        onProgress: (ModelInstallProgress) -> Unit,
    ) {
        check(staging.mkdirs()) { "Не удалось создать временный каталог модели" }
        val extracted = mutableSetOf<String>()
        var totalBytes = 0L
        val buffer = ByteArray(128 * 1024)
        onProgress(ModelInstallProgress(ModelInstallStage.EXTRACT, 0, REQUIRED_FILES.size.toLong()))
        BZip2CompressorInputStream(BufferedInputStream(FileInputStream(archive)), false).use { bz2 ->
            TarArchiveInputStream(bz2).use { tar ->
                while (true) {
                    if (cancelled()) throw ModelInstallCancelledException()
                    val entry = tar.nextTarEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    val parts = name.split('/').filter { it.isNotEmpty() }
                    check(!name.startsWith('/') && !name.contains(':') && parts.none { it == ".." }) {
                        "Архив модели содержит недопустимый путь"
                    }
                    if (entry.isDirectory) continue
                    check(entry.isFile) { "Архив модели содержит недопустимую ссылку" }
                    val fileName = parts.lastOrNull() ?: continue
                    if (fileName !in REQUIRED_FILES) continue
                    check(extracted.add(fileName)) { "Повторяющийся файл модели: $fileName" }
                    check(entry.size in 1L..MAX_FILE_BYTES) { "Некорректный размер файла модели" }
                    totalBytes += entry.size
                    check(totalBytes <= MAX_TOTAL_BYTES) { "Архив модели слишком велик" }
                    val output = File(staging, fileName)
                    var written = 0L
                    BufferedOutputStream(FileOutputStream(output)).use { sink ->
                        while (written < entry.size) {
                            if (cancelled()) throw ModelInstallCancelledException()
                            val count = tar.read(buffer, 0, minOf(buffer.size.toLong(), entry.size - written).toInt())
                            check(count > 0) { "Файл модели повреждён: $fileName" }
                            sink.write(buffer, 0, count)
                            written += count
                        }
                    }
                    onProgress(
                        ModelInstallProgress(
                            stage = ModelInstallStage.EXTRACT,
                            completed = extracted.size.toLong(),
                            total = REQUIRED_FILES.size.toLong(),
                            currentFile = fileName,
                        )
                    )
                }
            }
        }
        check(extracted == REQUIRED_FILES) {
            "В архиве отсутствуют файлы: ${(REQUIRED_FILES - extracted).joinToString()}"
        }
    }

    private fun deleteOwnedStaging() {
        archive.delete()
        if (staging.exists()) check(deleteTree(staging)) { "Не удалось очистить временные файлы" }
    }

    private fun deleteTree(directory: File): Boolean {
        if (!directory.exists()) return true
        // The only callers pass children of filesDir/speech_models with fixed names.
        if (directory.parentFile?.canonicalFile != root.canonicalFile) return false
        return directory.walkBottomUp().all { it.delete() || !it.exists() }
    }

    companion object {
        private const val MODEL_NAME = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11"
        private const val FULL_NAME = "supertonic-3-full"
        private const val FULL_MODEL_BASE_URL = "https://huggingface.co/Supertone/supertonic-3/resolve/main/onnx"
        private const val FULL_EXPECTED_BYTES = 400_000_000L
        private const val MAX_FULL_FILE_BYTES = 300L * 1024 * 1024
        private const val MAX_FULL_TOTAL_BYTES = 500L * 1024 * 1024
        private val FULL_ONNX_FILES = listOf(
            "duration_predictor.onnx", "text_encoder.onnx", "vector_estimator.onnx", "vocoder.onnx",
        )
        private val FULL_FILES = FULL_ONNX_FILES.toSet() + setOf("tts.json", "unicode_indexer.bin", "voice.bin")
        private const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$MODEL_NAME.tar.bz2"
        private const val MAX_FILE_BYTES = 400L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 700L * 1024 * 1024
        private const val MAX_ARCHIVE_BYTES = 500L * 1024 * 1024
        private val REQUIRED_FILES = setOf(
            "duration_predictor.int8.onnx",
            "text_encoder.int8.onnx",
            "vector_estimator.int8.onnx",
            "vocoder.int8.onnx",
            "tts.json",
            "unicode_indexer.bin",
            "voice.bin",
        )
    }
}
