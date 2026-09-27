package com.ozvuchka.app.speech

import java.text.BreakIterator
import java.util.Locale

/** The spoken text and its exact span in the original paragraph. */
internal data class SpeechChunk(
    val text: String,
    val start: Int,
    val end: Int,
)

/**
 * One unit of narration: a sentence (or a clause of a very long sentence) with its exact place
 * in the book. [paragraph] is -1 for the spoken chapter title.
 */
data class SpeechSegment(
    val chapter: Int,
    val paragraph: Int,
    val start: Int,
    val end: Int,
    val text: String,
    val language: String,
    val pause: SegmentPause,
)

/** What follows a segment; the player turns it into silence scaled by speed and user preference. */
enum class SegmentPause(val baseMs: Int) {
    CLAUSE(90),
    SENTENCE(320),
    PARAGRAPH(620),
    TITLE(900),
    CHAPTER_END(1200),
}

private val clauseBreak = setOf(',', ';', ':', '—', '–', ')')
private val sentenceEnd = setOf('.', '!', '?', '…', '»', '"', '”')

internal fun splitForSpeech(text: String, language: String, maxChars: Int = 260): List<SpeechChunk> {
    require(maxChars >= 16)
    if (text.isBlank()) return emptyList()
    val iterator = BreakIterator.getSentenceInstance(Locale.forLanguageTag(language))
    iterator.setText(text)
    val chunks = mutableListOf<SpeechChunk>()

    fun addRange(from: Int, to: Int) {
        var start = from
        var end = to
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (end > start) {
            val spoken = text.substring(start, end).replace(Regex("[\\s\\p{Z}]+"), " ")
            chunks += SpeechChunk(spoken, start, end)
        }
    }

    var sentenceStart = iterator.first()
    var sentenceEnd = iterator.next()
    while (sentenceEnd != BreakIterator.DONE) {
        var cursor = sentenceStart
        while (cursor < sentenceEnd) {
            while (cursor < sentenceEnd && text[cursor].isWhitespace()) cursor++
            if (cursor >= sentenceEnd) break
            if (sentenceEnd - cursor <= maxChars) {
                addRange(cursor, sentenceEnd)
                break
            }
            // A long sentence is cut where a reader would breathe: after a comma, semicolon or dash
            // close to the limit, otherwise between words. Never inside a word.
            val limit = (cursor + maxChars).coerceAtMost(sentenceEnd)
            val lowest = cursor + maxChars / 2
            val split = (limit downTo lowest).firstOrNull { index ->
                index < sentenceEnd && text[index].isWhitespace() && text[index - 1] in clauseBreak
            } ?: (limit downTo lowest).firstOrNull { text[it - 1].isWhitespace() }
                ?: limit
            addRange(cursor, split)
            cursor = split
        }
        sentenceStart = sentenceEnd
        sentenceEnd = iterator.next()
    }
    return chunks
}

/**
 * Picks the voice language of a sentence. Letters decide; a sentence switches away from the
 * book language only when the other script clearly dominates, so a Russian sentence quoting an
 * English brand name stays Russian.
 */
fun detectSpeechLanguage(text: CharSequence, fallback: String): String {
    var cyrillic = 0
    var latin = 0
    for (c in text) {
        when {
            c in 'Ѐ'..'ӿ' -> cyrillic++
            c in 'a'..'z' || c in 'A'..'Z' -> latin++
        }
    }
    if (cyrillic == 0 && latin == 0) return fallback
    return if (fallback == "en") {
        if (cyrillic >= 4 && cyrillic > latin * 2) "ru" else "en"
    } else {
        if (latin >= 4 && latin > cyrillic * 2) "en" else "ru"
    }
}

/** The dominant language of a chapter, estimated from its first few thousand characters. */
fun dominantLanguage(paragraphs: List<String>, fallback: String = "ru"): String {
    var cyrillic = 0
    var latin = 0
    var seen = 0
    for (paragraph in paragraphs) {
        for (c in paragraph) {
            when {
                c in 'Ѐ'..'ӿ' -> cyrillic++
                c in 'a'..'z' || c in 'A'..'Z' -> latin++
            }
        }
        seen += paragraph.length
        if (seen > 6_000) break
    }
    if (cyrillic == 0 && latin == 0) return fallback
    return if (cyrillic >= latin) "ru" else "en"
}

/**
 * Splits a chapter into narration segments. The chapter title is spoken first unless the text
 * already starts with it.
 */
fun chapterSegments(
    chapterIndex: Int,
    title: String?,
    paragraphs: List<String>,
    maxChars: Int = 260,
): List<SpeechSegment> {
    val chapterLanguage = dominantLanguage(paragraphs)
    val result = mutableListOf<SpeechSegment>()
    val spokenTitle = title?.trim().orEmpty()
    val firstParagraph = paragraphs.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    if (spokenTitle.isNotEmpty() && !sameText(spokenTitle, firstParagraph)) {
        result += SpeechSegment(
            chapter = chapterIndex,
            paragraph = -1,
            start = 0,
            end = spokenTitle.length,
            text = spokenTitle,
            language = detectSpeechLanguage(spokenTitle, chapterLanguage),
            pause = SegmentPause.TITLE,
        )
    }
    paragraphs.forEachIndexed { paragraphIndex, paragraph ->
        if (paragraph.isBlank()) return@forEachIndexed
        val paragraphLanguage = detectSpeechLanguage(paragraph, chapterLanguage)
        val chunks = splitForSpeech(paragraph, paragraphLanguage, maxChars)
        chunks.forEachIndexed { index, chunk ->
            val last = index == chunks.lastIndex
            val ending = chunk.text.trimEnd().lastOrNull()
            result += SpeechSegment(
                chapter = chapterIndex,
                paragraph = paragraphIndex,
                start = chunk.start,
                end = chunk.end,
                text = chunk.text,
                language = detectSpeechLanguage(chunk.text, paragraphLanguage),
                pause = when {
                    last -> SegmentPause.PARAGRAPH
                    ending != null && ending in sentenceEnd -> SegmentPause.SENTENCE
                    else -> SegmentPause.CLAUSE
                },
            )
        }
    }
    if (result.isNotEmpty()) {
        result[result.lastIndex] = result.last().copy(pause = SegmentPause.CHAPTER_END)
    }
    return result
}

/**
 * Splits a segment that opens a playback session so the first sound comes sooner: the first
 * clause of a long sentence is synthesized on its own.
 */
internal fun splitForQuickStart(
    segment: SpeechSegment,
    source: String,
    minHead: Int = 30,
    maxHead: Int = 120,
): List<SpeechSegment> {
    val text = segment.text
    if (text.length < 100) return listOf(segment)
    val cut = (minHead..minOf(maxHead, text.length - 25)).firstOrNull { index ->
        text[index] == ' ' && text[index - 1] in clauseBreak
    } ?: return listOf(segment)
    val boundary = originalOffset(source, segment.start, segment.end, cut)
    if (boundary <= segment.start || boundary >= segment.end) return listOf(segment)
    return listOf(
        segment.copy(end = boundary, text = text.substring(0, cut), pause = SegmentPause.CLAUSE),
        segment.copy(start = boundary, text = text.substring(cut + 1)),
    )
}

/**
 * Maps a position in spoken text (whitespace runs collapsed to one space) back to the source
 * span [from, to), so highlighting stays exact after a segment is split.
 */
internal fun originalOffset(source: String, from: Int, to: Int, spokenIndex: Int): Int {
    var spoken = 0
    var index = from
    val end = to.coerceAtMost(source.length)
    while (index < end && spoken < spokenIndex) {
        if (source[index].isWhitespace()) {
            while (index < end && source[index].isWhitespace()) index++
        } else {
            index++
        }
        spoken++
    }
    while (index < end && source[index].isWhitespace()) index++
    return index
}

private fun sameText(a: String, b: String): Boolean {
    fun key(value: String) = value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
    return key(a) == key(b)
}
