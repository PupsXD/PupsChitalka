package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import org.jsoup.Jsoup

private const val MAX_TEXT_BYTES = 40_000_000L

internal fun readTextBook(file: File, fallbackTitle: String, format: String): Book {
    if (file.length() > MAX_TEXT_BYTES) throw IOException("Текстовый файл слишком велик")
    if (format == "HTML") {
        val document = Jsoup.parse(file, null, "")
        val article = document.selectFirst("article") ?: document.selectFirst("main") ?: document.body()
        val content = article.clone()
        content.select("script, style, nav, aside, header, footer, form, iframe, .ad, .ads, .advertisement, .comments, #comments").remove()
        val paragraphs = htmlParagraphs(content)
        if (paragraphs.isEmpty()) throw IOException("В HTML нет текста для чтения")
        val title = document.selectFirst("h1")?.text()?.let(::normalizeText)
            ?.takeIf(String::isNotBlank)
            ?: document.title().let(::normalizeText).ifBlank { fallbackTitle }
        val author = document.selectFirst("meta[name=author]")?.attr("content")
            ?.let(::normalizeText).orEmpty()
        val warnings = if (content.select("img, svg").isNotEmpty()) {
            listOf("Изображения HTML не отображаются в текстовой читалке")
        } else emptyList()
        return Book(title = title, author = author, format = format, chapters = chaptersFromParagraphs(title, paragraphs), warnings = warnings)
    }

    val (text, guessedEncoding) = decodeText(file.readBytes())
    if (format == "MD") {
        val metadata = parseMarkdownMetadata(text)
        val firstHeading = text.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("# ") }
            ?.removePrefix("# ")?.let(::normalizeText)
            ?.takeUnless(::isChapterHeading)
        val title = metadata.first ?: firstHeading ?: fallbackTitle
        val chapters = markdownChapters(text, title)
        if (chapters.isEmpty()) throw IOException("В Markdown нет текста для чтения")
        return Book(
            title = title,
            author = metadata.second.orEmpty(),
            format = format,
            chapters = chapters,
            warnings = if (guessedEncoding) listOf("Кодировка файла определена предположительно как Windows-1251") else emptyList(),
        )
    }
    val chapters = chaptersFromParagraphs(fallbackTitle, plainParagraphs(text))
    if (chapters.isEmpty()) throw IOException("В текстовом файле нет текста для чтения")
    return Book(
        title = fallbackTitle,
        format = format,
        chapters = chapters,
        warnings = if (guessedEncoding) listOf("Кодировка файла определена предположительно как Windows-1251") else emptyList(),
    )
}

private fun decodeText(bytes: ByteArray): Pair<String, Boolean> {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
        return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8) to false
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE) to false
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
        return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE) to false
    }
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes)).toString() to false
    } catch (_: CharacterCodingException) {
        bytes.toString(Charset.forName("windows-1251")) to true
    }
}

private fun parseMarkdownMetadata(text: String): Pair<String?, String?> {
    if (!text.startsWith("---\n") && !text.startsWith("---\r\n")) return null to null
    val header = text.lineSequence().drop(1).takeWhile { it.trim() != "---" }.take(30).toList()
    val title = header.firstOrNull { it.startsWith("title:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.trim('"', '\'')
    val author = header.firstOrNull { it.startsWith("author:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()?.trim('"', '\'')
    return title?.takeIf(String::isNotBlank) to author?.takeIf(String::isNotBlank)
}

private fun markdownChapters(source: String, bookTitle: String): List<Chapter> {
    val text = if (source.startsWith("---\n") || source.startsWith("---\r\n")) {
        source.replace(Regex("(?s)^---\\r?\\n.*?\\r?\\n---\\r?\\n"), "")
    } else source
    val chapters = mutableListOf<Chapter>()
    val current = StringBuilder()
    var heading = bookTitle
    fun flush() {
        val paragraphs = plainParagraphs(current.toString())
        if (paragraphs.isNotEmpty()) chapters += Chapter(heading, paragraphs)
        current.clear()
    }
    text.lineSequence().forEach { sourceLine ->
        val line = sourceLine.trim()
        val headingMatch = Regex("^#{1,3}\\s+(.+?)\\s*#*$").matchEntire(line)
        if (headingMatch != null) {
            flush()
            heading = normalizeText(headingMatch.groupValues[1])
        } else if (line.startsWith("```")) {
            // Code fences are kept as plain text; their marker is not read aloud.
        } else {
            val plain = sourceLine
                .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
                .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
                .replace(Regex("^\\s{0,3}>\\s?"), "")
                .replace(Regex("(?<!\\w)[*_]{1,2}(?=\\S)|(?<=\\S)[*_]{1,2}(?!\\w)"), "")
                .replace("`", "")
            current.appendLine(plain)
        }
    }
    flush()
    return chapters
}
