package com.ozvuchka.app.speech

import java.text.BreakIterator
import java.util.Locale

/** The spoken text and its exact span in the original paragraph. */
internal data class SpeechChunk(
    val text: String,
    val start: Int,
    val end: Int,
)

internal fun splitForSpeech(text: String, language: String, maxChars: Int = 360): List<SpeechChunk> {
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
            val limit = (cursor + maxChars).coerceAtMost(sentenceEnd)
            val split = (limit downTo cursor + maxChars / 2)
                .firstOrNull { text[it - 1].isWhitespace() }
                ?: limit
            addRange(cursor, split)
            cursor = split
        }
        sentenceStart = sentenceEnd
        sentenceEnd = iterator.next()
    }
    return chunks
}
