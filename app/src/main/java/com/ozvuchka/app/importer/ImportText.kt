package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

private val whiteSpace = Regex("[\\t\\n\\r\\u00A0\\u2000-\\u200B ]+")
private val chapterHeading = Regex(
    "^(?i:(?:глава|chapter|часть|part)\\s+(?:\\d+|[ivxlcdm]+)(?:[. :—–-].*)?|" +
        "пролог|эпилог|prologue|epilogue)(?:[. :—–-].*)?$",
)
private val blockTags = setOf(
    "address", "article", "blockquote", "dd", "div", "dt", "h1", "h2", "h3", "h4", "h5", "h6",
    "li", "p", "section", "td", "th", "tr", "poem", "stanza", "v", "subtitle", "text-author",
    "epigraph",
)
private val ignoredTags = setOf("script", "style", "nav", "aside", "footer", "header", "noscript", "rt", "rp")

internal fun normalizeText(value: String): String = value
    .replace('\u00AD'.toString(), "")
    .replace(whiteSpace, " ")
    .trim()

internal fun htmlParagraphs(root: Element): List<String> = htmlBlocks(root).map { it.first }

/**
 * Paragraphs of an HTML or FB2 fragment with their look: headings, captions, table rows, and
 * pictures that [image] stores (it gets the `img` or `image` element; null leaves the picture out,
 * then its description is kept as text).
 */
internal fun htmlBlocks(root: Element, image: (Element) -> ParagraphStyle? = { null }): List<Pair<String, ParagraphStyle?>> {
    val result = mutableListOf<Pair<String, ParagraphStyle?>>()
    val current = StringBuilder()
    var currentKind: ParagraphKind? = null
    fun flush() {
        val paragraph = normalizeText(current.toString())
        if (paragraph.isNotEmpty()) result += paragraph to currentKind?.let { ParagraphStyle(it) }
        current.clear()
    }
    fun visit(node: Node) {
        when (node) {
            is TextNode -> current.append(node.wholeText)
            is Element -> {
                val tag = node.normalName().substringAfterLast(':')
                if (tag in ignoredTags || node.hasAttr("hidden") || node.attr("aria-hidden") == "true") return
                if (tag == "br") {
                    flush()
                    return
                }
                if (tag == "img" || tag == "image") {
                    val style = runCatching { image(node) }.getOrNull()
                    flush()
                    if (style != null) {
                        result += "" to style
                    } else {
                        val alt = normalizeText(node.attr("alt"))
                        if (alt.isNotEmpty()) result += "Иллюстрация: $alt" to null
                    }
                    return
                }
                if (tag == "tr") {
                    flush()
                    val cells = node.children().filter { it.normalName().substringAfterLast(':') in setOf("td", "th") }
                    val texts = cells.map { cell -> normalizeText(cell.text()).replace('\t', ' ') }
                    if (texts.any { it.isNotEmpty() }) {
                        val header = cells.isNotEmpty() && cells.all { it.normalName().endsWith("th") }
                        result += texts.joinToString("\t") to ParagraphStyle(if (header) ParagraphKind.TABLE_HEADER else ParagraphKind.TABLE_ROW)
                    }
                    // Pictures inside cells still show, after the row.
                    node.select("img, image").forEach(::visit)
                    return
                }
                val classes = node.className().lowercase()
                val kind = when {
                    tag == "h1" || tag == "h2" -> ParagraphKind.HEADING
                    tag in setOf("h3", "h4", "h5", "h6", "subtitle") -> ParagraphKind.SUBHEADING
                    tag == "figcaption" || tag == "caption" || "caption" in classes -> ParagraphKind.CAPTION
                    tag in setOf("div", "p", "blockquote", "section") && ("note" in classes || "sidebar" in classes) -> ParagraphKind.NOTE
                    else -> null
                }
                val block = tag in blockTags || kind != null
                if (block) flush()
                val outer = currentKind
                if (kind != null) currentKind = kind
                node.childNodes().forEach(::visit)
                if (block) flush()
                currentKind = outer
            }
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(root)
    flush()
    return result
}

/** A long chapter without headings as parts of about [maxChapterChars] letters; looks stay with their paragraphs. */
internal fun splitLongChapter(chapter: Chapter, maxChapterChars: Int = 30_000): List<Chapter> {
    if (chapter.paragraphs.sumOf { it.length } <= maxChapterChars) return listOf(chapter)
    val parts = ArrayList<Chapter>()
    var from = 0
    var size = 0
    fun cut(until: Int) {
        val styles = chapter.styles.filterKeys { it in from until until }.mapKeys { it.key - from }
        val title = if (parts.isEmpty()) chapter.title else "Часть ${parts.size + 1}"
        parts += Chapter(title, chapter.paragraphs.subList(from, until).toList(), styles = styles)
        from = until
        size = 0
    }
    chapter.paragraphs.forEachIndexed { index, paragraph ->
        if (index > from && size >= maxChapterChars) cut(index)
        size += paragraph.length
    }
    if (from < chapter.paragraphs.size) cut(chapter.paragraphs.size)
    return parts
}

/** Paragraphs with looks as a chapter: the looks go to the paragraphs' indexes. */
internal fun chapterOf(title: String, blocks: List<Pair<String, ParagraphStyle?>>): Chapter {
    val styles = HashMap<Int, ParagraphStyle>()
    blocks.forEachIndexed { index, (_, style) -> if (style != null) styles[index] = style }
    return Chapter(title, blocks.map { it.first }, styles = styles)
}

internal fun plainParagraphs(text: String): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var previousLine = ""
    fun flush() {
        val value = normalizeText(current.toString())
        if (value.isNotEmpty()) result += value
        current.clear()
    }
    text.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { sourceLine ->
        val line = sourceLine.trim()
        if (line.isEmpty()) {
            flush()
            previousLine = ""
        } else if (chapterHeading.matches(line)) {
            flush()
            result += line
            previousLine = ""
        } else {
            val startsNewParagraph = line.startsWith('—') || line.startsWith('–') ||
                ((sourceLine.startsWith("    ") || sourceLine.startsWith('\t')) && current.length > 80) ||
                (previousLine.isNotEmpty() && previousLine.length < 120 &&
                    previousLine.last() in ".!?…" && line.first().isUpperCase())
            if (current.isNotEmpty() && startsNewParagraph) flush()
            if (current.isNotEmpty()) {
                if (current.last() == '-' && line.firstOrNull()?.isLowerCase() == true) {
                    current.deleteCharAt(current.lastIndex)
                } else {
                    current.append(' ')
                }
            }
            current.append(line)
            if (current.length > 1_500 && line.last() in ".!?…") flush()
            previousLine = line
        }
    }
    flush()
    return result
}

internal fun isChapterHeading(value: String): Boolean =
    value.length <= 120 && chapterHeading.matches(value.trim())

internal fun chaptersFromParagraphs(
    bookTitle: String,
    paragraphs: List<String>,
    maxChapterChars: Int = 30_000,
): List<Chapter> {
    val nonEmpty = paragraphs.map(::normalizeText).filter(String::isNotEmpty)
    if (nonEmpty.isEmpty()) return emptyList()
    val chapters = mutableListOf<Chapter>()
    val current = mutableListOf<String>()
    var chapterTitle = bookTitle
    var characterCount = 0
    var hasExplicitHeading = false
    fun flush() {
        if (current.isNotEmpty()) chapters += Chapter(chapterTitle, current.toList())
        current.clear()
        characterCount = 0
    }
    nonEmpty.forEach { paragraph ->
        if (isChapterHeading(paragraph)) {
            flush()
            chapterTitle = paragraph
            hasExplicitHeading = true
        } else {
            if (!hasExplicitHeading && characterCount >= maxChapterChars) {
                flush()
                chapterTitle = "Часть ${chapters.size + 1}"
            }
            current += paragraph
            characterCount += paragraph.length
        }
    }
    flush()
    return chapters
}
