package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
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

internal fun htmlParagraphs(root: Element): List<String> {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    fun flush() {
        val paragraph = normalizeText(current.toString())
        if (paragraph.isNotEmpty()) result += paragraph
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
                if (tag == "img") {
                    val alt = normalizeText(node.attr("alt"))
                    if (alt.isNotEmpty()) {
                        flush()
                        result += "Иллюстрация: $alt"
                    }
                    return
                }
                val block = tag in blockTags
                if (block) flush()
                node.childNodes().forEach(::visit)
                if (block) flush()
            }
            else -> node.childNodes().forEach(::visit)
        }
    }
    visit(root)
    flush()
    return result
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
