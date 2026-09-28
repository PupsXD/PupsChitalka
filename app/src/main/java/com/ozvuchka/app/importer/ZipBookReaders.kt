package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.zip.ZipFile
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

private const val MAX_ZIP_ENTRIES = 12_000
private const val MAX_TOTAL_TEXT_BYTES = 100L * 1024 * 1024

/** Reads individual ZIP entries in memory; no archive paths are ever extracted to disk. */
private class SafeZip(file: File) : Closeable {
    private val zip = ZipFile(file)
    private var totalRead = 0L

    init {
        var count = 0
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            count++
            if (count > MAX_ZIP_ENTRIES || entry.name.length > 1_024) {
                zip.close()
                throw IOException("Архив слишком велик или повреждён")
            }
        }
    }

    fun has(path: String): Boolean = zip.getEntry(path) != null

    fun names(): List<String> = zip.entries().asSequence().map { it.name }.toList()

    fun read(path: String, maxBytes: Int): ByteArray {
        val entry = zip.getEntry(path) ?: throw IOException("В архиве нет файла: $path")
        if (entry.isDirectory || entry.size > maxBytes) {
            throw IOException("Раздел книги слишком велик: $path")
        }
        val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (output.size().toLong() + count > maxBytes || totalRead + count > MAX_TOTAL_TEXT_BYTES) {
                    throw IOException("Слишком большой объём текста в архиве")
                }
                output.write(buffer, 0, count)
                totalRead += count
            }
        }
        return output.toByteArray()
    }

    override fun close() = zip.close()
}

private fun parseXml(bytes: ByteArray): Document =
    Jsoup.parse(ByteArrayInputStream(bytes), null, "", Parser.xmlParser())

private fun Element.localName(): String = normalName().substringAfterLast(':')

private fun Element.firstByLocalName(name: String): Element? =
    getAllElements().firstOrNull { it.localName().equals(name, ignoreCase = true) }

private fun Element.childrenByLocalName(name: String): List<Element> =
    children().filter { it.localName().equals(name, ignoreCase = true) }

private fun resolveZipPath(baseFile: String, relative: String): String? {
    val href = relative.substringBefore('#').substringBefore('?')
    if (href.isBlank() || href.startsWith('/') || href.startsWith('\\') ||
        Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(href)
    ) return null
    val decoded = runCatching { URLDecoder.decode(href.replace("+", "%2B"), "UTF-8") }.getOrNull()
        ?: return null
    val segments = mutableListOf<String>()
    val joined = baseFile.substringBeforeLast('/', "").let { base -> "$base/$decoded" }
    for (segment in joined.replace('\\', '/').split('/')) {
        when (segment) {
            "", "." -> Unit
            ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
            else -> segments += segment
        }
    }
    return segments.joinToString("/")
}

internal fun readEpub(file: File, fallbackTitle: String): Book = SafeZip(file).use { zip ->
    val container = parseXml(zip.read("META-INF/container.xml", 1_000_000))
    val packagePath = container.firstByLocalName("rootfile")?.attr("full-path")
        ?.takeIf(String::isNotBlank)
        ?: throw IOException("Не найдено описание EPUB")
    if (packagePath.startsWith('/') || packagePath.contains("..")) {
        throw IOException("Некорректный путь к описанию EPUB")
    }
    val packageDocument = parseXml(zip.read(packagePath, 5_000_000))
    val metadata = packageDocument.firstByLocalName("metadata")
    val title = metadata?.firstByLocalName("title")?.text()?.let(::normalizeText)
        ?.takeIf(String::isNotBlank) ?: fallbackTitle
    val author = metadata?.firstByLocalName("creator")?.text()?.let(::normalizeText).orEmpty()
    val manifest = packageDocument.firstByLocalName("manifest")
        ?: throw IOException("В EPUB нет списка разделов")
    val items = manifest.childrenByLocalName("item").associateBy { it.attr("id") }
    val spine = packageDocument.firstByLocalName("spine")
        ?: throw IOException("В EPUB нет порядка чтения")
    val chapters = mutableListOf<Chapter>()
    var imageCount = 0
    for (reference in spine.childrenByLocalName("itemref")) {
        val item = items[reference.attr("idref")] ?: continue
        if (item.attr("properties").split(Regex("\\s+")).contains("nav") ||
            reference.attr("linear").equals("no", ignoreCase = true)
        ) continue
        val mediaType = item.attr("media-type").lowercase()
        if (mediaType != "application/xhtml+xml" && mediaType != "text/html") continue
        val path = resolveZipPath(packagePath, item.attr("href")) ?: continue
        if (!zip.has(path)) continue
        val chapterDocument = Jsoup.parse(ByteArrayInputStream(zip.read(path, 10_000_000)), null, "")
        val body = chapterDocument.body()
        imageCount += body.select("img, image, svg").size
        val paragraphs = htmlParagraphs(body).toMutableList()
        if (paragraphs.isEmpty()) continue
        val chapterTitle = body.selectFirst("h1, h2, h3")?.text()?.let(::normalizeText)
            ?.takeIf(String::isNotBlank) ?: "Глава ${chapters.size + 1}"
        if (paragraphs.firstOrNull() == chapterTitle) paragraphs.removeAt(0)
        if (paragraphs.isNotEmpty()) chapters += Chapter(chapterTitle, paragraphs)
        if (chapters.size > 2_000) throw IOException("В EPUB слишком много разделов")
    }
    if (chapters.isEmpty()) throw IOException("В EPUB не найден читаемый текст; возможно, книга защищена DRM")
    val warnings = buildList {
        if (imageCount > 0) add("Иллюстрации EPUB не отображаются в текстовой читалке")
    }
    Book(title = title, author = author, format = "EPUB", chapters = chapters, warnings = warnings)
}

internal fun readFb2(file: File, fallbackTitle: String): Book {
    val bytes = if (file.inputStream().use { it.read() } == 'P'.code) {
        SafeZip(file).use { zip ->
            val fb2Name = zip.names().firstOrNull { it.endsWith(".fb2", ignoreCase = true) }
                ?: throw IOException("В архиве нет файла FB2")
            zip.read(fb2Name, 40_000_000)
        }
    } else {
        if (file.length() > 40_000_000) throw IOException("Файл FB2 слишком велик")
        file.readBytes()
    }
    val document = parseXml(bytes)
    val titleInfo = document.firstByLocalName("title-info")
    val title = titleInfo?.firstByLocalName("book-title")?.text()?.let(::normalizeText)
        ?.takeIf(String::isNotBlank) ?: fallbackTitle
    val authorNode = titleInfo?.firstByLocalName("author")
    val author = listOf("first-name", "middle-name", "last-name")
        .mapNotNull { name -> authorNode?.firstByLocalName(name)?.text()?.let(::normalizeText) }
        .filter(String::isNotBlank)
        .joinToString(" ")
        .ifBlank { authorNode?.firstByLocalName("nickname")?.text()?.let(::normalizeText).orEmpty() }
    val chapters = mutableListOf<Chapter>()
    var skippedNotes = false
    fun visitSection(section: Element) {
        val heading = section.childrenByLocalName("title").firstOrNull()?.text()?.let(::normalizeText)
            ?.takeIf(String::isNotBlank) ?: "Глава ${chapters.size + 1}"
        val ownContent = section.clone()
        ownContent.childrenByLocalName("section").forEach { it.remove() }
        ownContent.childrenByLocalName("title").forEach { it.remove() }
        val paragraphs = htmlParagraphs(ownContent)
        if (paragraphs.isNotEmpty()) chapters += Chapter(heading, paragraphs)
        if (chapters.size > 2_000) throw IOException("В FB2 слишком много разделов")
        section.childrenByLocalName("section").forEach(::visitSection)
    }
    val bodies = document.getAllElements().filter { it.localName() == "body" }
    bodies.forEach { body ->
        if (body.attr("name").equals("notes", ignoreCase = true)) {
            skippedNotes = true
            return@forEach
        }
        val intro = body.clone()
        intro.childrenByLocalName("section").forEach { it.remove() }
        val introParagraphs = htmlParagraphs(intro)
        if (introParagraphs.isNotEmpty()) chapters += Chapter("Введение", introParagraphs)
        body.childrenByLocalName("section").forEach(::visitSection)
    }
    if (chapters.isEmpty()) throw IOException("В FB2 не найден читаемый текст")
    val warnings = buildList {
        if (document.getAllElements().any { it.localName() == "image" }) {
            add("Иллюстрации FB2 не отображаются в текстовой читалке")
        }
        if (skippedNotes) add("Раздел с примечаниями FB2 не импортирован")
    }
    return Book(title = title, author = author, format = "FB2", chapters = chapters, warnings = warnings)
}

internal fun readDocx(file: File, fallbackTitle: String): Book = SafeZip(file).use { zip ->
    val core = if (zip.has("docProps/core.xml")) parseXml(zip.read("docProps/core.xml", 1_000_000)) else null
    val title = core?.firstByLocalName("title")?.text()?.let(::normalizeText)
        ?.takeIf(String::isNotBlank) ?: fallbackTitle
    val author = core?.firstByLocalName("creator")?.text()?.let(::normalizeText).orEmpty()
    val document = parseXml(zip.read("word/document.xml", 40_000_000))
    val paragraphs = mutableListOf<String>()
    val chapters = mutableListOf<Chapter>()
    var chapterTitle = title
    var foundHeading = false
    fun flush() {
        if (paragraphs.isNotEmpty()) chapters += Chapter(chapterTitle, paragraphs.toList())
        paragraphs.clear()
    }
    for (paragraph in document.getAllElements().filter { it.localName() == "p" }) {
        val text = buildString {
            for (child in paragraph.getAllElements().drop(1)) {
                when (child.localName()) {
                    "t" -> child.textNodes().forEach { append(it.wholeText) }
                    "tab" -> append(' ')
                    "br", "cr" -> append(' ')
                }
            }
        }.let(::normalizeText)
        if (text.isEmpty()) continue
        val style = paragraph.firstByLocalName("pStyle")?.let { it.attr("w:val").ifBlank { it.attr("val") } }
            .orEmpty()
        val isHeading = style.matches(Regex("(?i)(heading|заголовок)[ _-]*[1-6]")) || isChapterHeading(text)
        if (isHeading) {
            flush()
            chapterTitle = text
            foundHeading = true
        } else {
            paragraphs += text
        }
    }
    flush()
    if (chapters.isEmpty()) throw IOException("В DOCX не найден читаемый текст")
    val result = if (foundHeading) chapters else chaptersFromParagraphs(title, chapters.flatMap(Chapter::paragraphs))
    val warnings = buildList {
        if (zip.names().any { it.startsWith("word/media/") }) {
            add("Изображения DOCX не отображаются в текстовой читалке")
        }
    }
    Book(title = title, author = author, format = "DOCX", chapters = result, warnings = warnings)
}

private const val MAX_COVER_BYTES = 12_000_000

/**
 * The cover image of an EPUB: the manifest item marked «cover-image» (EPUB 3), the one named by
 * `<meta name="cover">` (EPUB 2), or an image whose id or file name says «cover».
 */
internal fun readEpubCover(file: File): ByteArray? = SafeZip(file).use { zip ->
    val container = parseXml(zip.read("META-INF/container.xml", 1_000_000))
    val packagePath = container.firstByLocalName("rootfile")?.attr("full-path")?.takeIf(String::isNotBlank) ?: return null
    val href = epubCoverHref(parseXml(zip.read(packagePath, 5_000_000))) ?: return null
    val path = resolveZipPath(packagePath, href)?.takeIf(zip::has) ?: return null
    zip.read(path, MAX_COVER_BYTES)
}

internal fun epubCoverHref(packageDocument: Document): String? {
    val items = packageDocument.firstByLocalName("manifest")?.childrenByLocalName("item").orEmpty()
    fun isImage(item: Element) = item.attr("media-type").startsWith("image/")
    items.firstOrNull { item -> isImage(item) && item.attr("properties").split(Regex("\\s+")).contains("cover-image") }
        ?.let { return it.attr("href") }
    val coverId = packageDocument.firstByLocalName("metadata")?.childrenByLocalName("meta")
        ?.firstOrNull { it.attr("name").equals("cover", ignoreCase = true) }?.attr("content")
    items.firstOrNull { isImage(it) && coverId != null && it.attr("id") == coverId }?.let { return it.attr("href") }
    return items.firstOrNull { item ->
        isImage(item) && (item.attr("id").contains("cover", ignoreCase = true) || item.attr("href").contains("cover", ignoreCase = true))
    }?.attr("href")
}

/** The FB2 cover: `<coverpage><image l:href="#id"/>` points at a base64 `<binary>`. */
internal fun readFb2Cover(file: File): ByteArray? {
    val bytes = if (file.inputStream().use { it.read() } == 'P'.code) {
        SafeZip(file).use { zip ->
            val name = zip.names().firstOrNull { it.endsWith(".fb2", ignoreCase = true) } ?: return null
            zip.read(name, 40_000_000)
        }
    } else {
        if (file.length() > 40_000_000) return null
        file.readBytes()
    }
    return fb2Cover(parseXml(bytes))
}

internal fun fb2Cover(document: Document): ByteArray? {
    val image = document.firstByLocalName("coverpage")?.getAllElements()?.firstOrNull { it.localName() == "image" } ?: return null
    val reference = image.attributes().firstOrNull { it.key.endsWith("href", ignoreCase = true) }?.value
        ?.removePrefix("#")?.takeIf(String::isNotBlank) ?: return null
    val binary = document.getAllElements().firstOrNull { it.localName() == "binary" && it.attr("id") == reference } ?: return null
    return runCatching { java.util.Base64.getMimeDecoder().decode(binary.text()) }.getOrNull()?.takeIf { it.size in 64..MAX_COVER_BYTES }
}
