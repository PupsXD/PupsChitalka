package com.ozvuchka.app.importer

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.math.sqrt

private const val MAX_PDF_PAGES = 3_000
private const val PAGE_GROUP_SIZE = 12

internal suspend fun readPdf(
    context: Context,
    file: File,
    fallbackTitle: String,
    onProgress: (String) -> Unit,
): Book {
    PDFBoxResourceLoader.init(context.applicationContext)
    PDDocument.load(file).use { document ->
        if (document.numberOfPages == 0) throw IOException("PDF не содержит страниц")
        if (document.numberOfPages > MAX_PDF_PAGES) throw IOException("PDF содержит больше 3000 страниц")
        if (!document.currentAccessPermission.canExtractContent()) {
            throw IOException("В PDF запрещено извлечение текста")
        }
        val title = document.documentInformation.title?.let(::normalizeText)
            ?.takeIf(String::isNotBlank) ?: fallbackTitle
        val author = document.documentInformation.author?.let(::normalizeText).orEmpty()
        val stripper = PDFTextStripper().apply { sortByPosition = true }
        val renderer = PDFRenderer(document)
        val pages = mutableListOf<Pair<Int, List<String>>>()
        var ocr: TessBaseAPI? = null
        var ocrAttempted = false
        var ocrPages = 0
        var unreadablePages = 0
        var extractionErrors = 0
        try {
            for (pageIndex in 0 until document.numberOfPages) {
                currentCoroutineContext().ensureActive()
                val pageNumber = pageIndex + 1
                if (pageNumber == 1 || pageNumber % 5 == 0 || pageNumber == document.numberOfPages) {
                    onProgress("PDF: страница $pageNumber из ${document.numberOfPages}")
                }
                val extracted = try {
                    stripper.startPage = pageNumber
                    stripper.endPage = pageNumber
                    stripper.getText(document)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    extractionErrors++
                    ""
                }
                var text = extracted
                if (text.count(Char::isLetterOrDigit) < 80) {
                    if (!ocrAttempted) {
                        ocrAttempted = true
                        ocr = runCatching { createOcr(context) }.getOrNull()
                    }
                    val engine = ocr
                    if (engine != null) {
                        onProgress("OCR: страница $pageNumber из ${document.numberOfPages}")
                        val recognized = try {
                            recognizePage(document, renderer, pageIndex, engine)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            ""
                        }
                        if (recognized.count(Char::isLetterOrDigit) > text.count(Char::isLetterOrDigit)) {
                            text = recognized
                            ocrPages++
                        }
                    }
                }
                val cleanLines = text.lineSequence()
                    .filterNot { it.trim().matches(Regex("\\d{1,4}")) }
                    .joinToString("\n")
                val paragraphs = plainParagraphs(cleanLines)
                if (paragraphs.isEmpty()) unreadablePages++
                pages += pageNumber to paragraphs
            }
        } finally {
            ocr?.recycle()
        }
        val chapters = groupPdfPages(pages)
        if (chapters.isEmpty()) {
            throw IOException("Из PDF не удалось получить текст; проверьте качество скана и языковые данные OCR")
        }
        val warnings = buildList {
            add("PDF преобразован в поток текста: колонки, таблицы, сноски и порядок чтения могут требовать проверки по оригиналу")
            if (ocrPages > 0) add("Распознавание OCR использовано на $ocrPages страницах; возможны ошибки в словах и знаках")
            if (ocrAttempted && ocr == null) add("OCR недоступен: не найдены русская и английская языковые модели")
            if (unreadablePages > 0) add("Страниц без читаемого текста: $unreadablePages")
            if (extractionErrors > 0) add("Ошибок извлечения PDF: $extractionErrors; эти страницы проверены через OCR")
        }
        return Book(title = title, author = author, format = "PDF", chapters = chapters, warnings = warnings)
    }
}

private fun groupPdfPages(pages: List<Pair<Int, List<String>>>): List<Chapter> {
    val chapters = mutableListOf<Chapter>()
    val current = mutableListOf<String>()
    var chapterStart = 1
    var lastPage = 1
    var explicitTitle: String? = null
    fun flush() {
        if (current.isNotEmpty()) {
            val title = explicitTitle ?: if (chapterStart == lastPage) {
                "Страница $chapterStart"
            } else {
                "Страницы $chapterStart–$lastPage"
            }
            chapters += Chapter(title, current.toList())
        }
        current.clear()
        explicitTitle = null
    }
    pages.forEach { (pageNumber, paragraphs) ->
        if (explicitTitle == null && current.isNotEmpty() && pageNumber - chapterStart >= PAGE_GROUP_SIZE) {
            flush()
            chapterStart = pageNumber
        }
        lastPage = pageNumber
        for (paragraph in paragraphs) {
            if (isChapterHeading(paragraph)) {
                flush()
                chapterStart = pageNumber
                explicitTitle = paragraph
            } else {
                current += paragraph
            }
        }
    }
    flush()
    return chapters
}

private fun recognizePage(
    document: PDDocument,
    renderer: PDFRenderer,
    pageIndex: Int,
    engine: TessBaseAPI,
): String {
    val box = document.getPage(pageIndex).cropBox
    val areaAt72Dpi = box.width.toDouble() * box.height.toDouble()
    if (!areaAt72Dpi.isFinite() || areaAt72Dpi <= 0.0) return ""
    val maximumDpi = 72.0 * sqrt(12_000_000.0 / areaAt72Dpi)
    if (maximumDpi < 50.0) return ""
    val bitmap = renderer.renderImageWithDPI(pageIndex, minOf(220.0, maximumDpi).toFloat(), ImageType.RGB)
    return try {
        engine.setImage(bitmap)
        engine.getUTF8Text().orEmpty()
    } finally {
        engine.clear()
        bitmap.recycle()
    }
}

private fun createOcr(context: Context): TessBaseAPI? {
    val dataDirectory = File(context.filesDir, "ocr")
    val tessdata = File(dataDirectory, "tessdata")
    if (!tessdata.exists() && !tessdata.mkdirs()) return null
    for (language in listOf("rus", "eng")) {
        val name = "$language.traineddata"
        val destination = File(tessdata, name)
        if (!destination.exists() || destination.length() < 1_024) {
            val staging = File(tessdata, "$name.part")
            try {
                context.assets.open("tessdata/$name").use { source ->
                    staging.outputStream().use { target -> source.copyTo(target) }
                }
                if (!staging.renameTo(destination)) {
                    throw IOException("Не удалось подготовить OCR-модель $language")
                }
            } finally {
                staging.delete()
            }
        }
    }
    val engine = TessBaseAPI()
    if (!engine.init(dataDirectory.absolutePath, "rus+eng")) {
        engine.recycle()
        return null
    }
    engine.setPageSegMode(TessBaseAPI.PageSegMode.PSM_AUTO)
    return engine
}
