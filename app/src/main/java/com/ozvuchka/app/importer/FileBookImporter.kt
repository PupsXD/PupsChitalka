package com.ozvuchka.app.importer

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ozvuchka.app.data.Book
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Imports a user-selected document into the app's chapter/paragraph model. */
object FileBookImporter {
    private const val MAX_SOURCE_BYTES = 150L * 1024 * 1024

    /** [onProgress] is called from the IO worker; the UI should dispatch updates to its main thread. */
    suspend fun importBook(
        context: Context,
        uri: Uri,
        onProgress: (String) -> Unit = {},
    ): Book = withContext(Dispatchers.IO) {
        val displayName = displayName(context, uri)
        val stem = if (displayName.endsWith(".fb2.zip", ignoreCase = true)) {
            displayName.dropLast(8)
        } else {
            displayName.substringBeforeLast('.', displayName)
        }
        val fallbackTitle = stem
            .replace('_', ' ')
            .trim()
            .ifBlank { "Без названия" }
        val temporaryFile = File.createTempFile("book-import-", ".tmp", context.cacheDir)
        try {
            onProgress("Читаем выбранный файл…")
            context.contentResolver.openInputStream(uri)?.use { source ->
                temporaryFile.outputStream().use { target ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var nextUpdate = 8L * 1024 * 1024
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = source.read(buffer)
                        if (count < 0) break
                        copied += count
                        if (copied > MAX_SOURCE_BYTES) {
                            throw IOException("Файл больше 150 МБ; импортируйте меньший документ")
                        }
                        target.write(buffer, 0, count)
                        if (copied >= nextUpdate) {
                            onProgress("Скопировано ${copied / (1024 * 1024)} МБ…")
                            nextUpdate += 8L * 1024 * 1024
                        }
                    }
                }
            } ?: throw IOException("Не удалось открыть выбранный файл")

            val format = detectFormat(temporaryFile, displayName, context.contentResolver.getType(uri))
            onProgress("Разбираем формат $format…")
            val book = when (format) {
                "EPUB" -> readEpub(temporaryFile, fallbackTitle)
                "FB2" -> readFb2(temporaryFile, fallbackTitle)
                "PDF" -> readPdf(context, temporaryFile, fallbackTitle, onProgress)
                "DOCX" -> readDocx(temporaryFile, fallbackTitle)
                "TXT", "HTML", "MD" -> readTextBook(temporaryFile, fallbackTitle, format)
                else -> throw IOException("Формат файла не поддерживается")
            }
            if (book.chapters.isEmpty() || book.chapters.all { it.paragraphs.isEmpty() }) {
                throw IOException("В документе не найден текст для чтения")
            }
            onProgress("Книга готова")
            book.copy(source = uri.toString())
        } finally {
            temporaryFile.delete()
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val column = OpenableColumns.DISPLAY_NAME
        val queried = runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(cursor.getColumnIndexOrThrow(column)) else null
            }
        }.getOrNull()
        return (queried ?: uri.lastPathSegment ?: "book")
            .substringAfterLast('/')
            .substringAfterLast('\\')
    }

    private fun detectFormat(file: File, fileName: String, mime: String?): String {
        val lowerName = fileName.lowercase(Locale.ROOT)
        val extensionFormat = when {
            lowerName.endsWith(".epub") -> "EPUB"
            lowerName.endsWith(".fb2") || lowerName.endsWith(".fb2.zip") -> "FB2"
            lowerName.endsWith(".pdf") -> "PDF"
            lowerName.endsWith(".docx") -> "DOCX"
            lowerName.endsWith(".txt") -> "TXT"
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerName.endsWith(".xhtml") -> "HTML"
            lowerName.endsWith(".md") || lowerName.endsWith(".markdown") -> "MD"
            else -> null
        }
        val magic = FileInputStream(file).use { input ->
            ByteArray(512).let { bytes -> bytes.copyOf(input.read(bytes).coerceAtLeast(0)) }
        }
        if (magic.size >= 4 && magic.copyOfRange(0, 4).contentEquals("%PDF".toByteArray())) return "PDF"
        if (magic.size >= 4 && magic[0] == 'P'.code.toByte() && magic[1] == 'K'.code.toByte()) {
            ZipFile(file).use { zip ->
                if (zip.getEntry("META-INF/container.xml") != null) return "EPUB"
                if (zip.getEntry("word/document.xml") != null) return "DOCX"
                if (extensionFormat == "FB2") return "FB2"
            }
            throw IOException("Неизвестный книжный ZIP-архив")
        }
        val preview = magic.toString(Charsets.UTF_8).trimStart('\uFEFF', ' ', '\r', '\n', '\t')
        if (preview.contains("<FictionBook", ignoreCase = true)) return "FB2"
        if (preview.contains("<html", ignoreCase = true) || preview.contains("<!doctype html", ignoreCase = true)) return "HTML"
        if (extensionFormat != null) return extensionFormat
        return when (mime?.lowercase(Locale.ROOT)) {
            "text/plain" -> "TXT"
            "text/html", "application/xhtml+xml" -> "HTML"
            "text/markdown", "text/x-markdown" -> "MD"
            "application/fb2+xml", "application/x-fictionbook+xml" -> "FB2"
            "application/epub+zip" -> "EPUB"
            "application/pdf" -> "PDF"
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOCX"
            else -> throw IOException("Не удалось определить формат файла")
        }
    }
}
