package com.ozvuchka.app.data

import java.util.UUID
import kotlin.math.roundToInt

data class Chapter(
    val title: String,
    val paragraphs: List<String>,
    val sourceUrl: String? = null,
    val nextUrl: String? = null,
)

data class Book(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val author: String = "",
    val format: String,
    val chapters: List<Chapter>,
    val source: String? = null,
    val currentChapter: Int = 0,
    val chapterProgress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
    val lastOpenedAt: Long = 0L,
) {
    /** Reading progress across the whole book, from 0 to 1. */
    val overallProgress: Float
        get() = if (chapters.isEmpty()) 0f else ((currentChapter + chapterProgress) / chapters.size).coerceIn(0f, 1f)

    /** Paragraph and character offset that [chapterProgress] points to. */
    fun position(): Pair<Int, Int> {
        val paragraphs = chapters.getOrNull(currentChapter)?.paragraphs.orEmpty()
        if (paragraphs.isEmpty()) return 0 to 0
        // Float progress loses a little precision; nudge by a hair and round, so a saved
        // position maps back to the same character instead of one before it.
        val location = chapterProgress.coerceIn(0f, 1f) * paragraphs.size + 1e-4f
        val paragraph = location.toInt().coerceIn(0, paragraphs.lastIndex)
        val length = paragraphs[paragraph].length
        val offset = ((location - paragraph) * length).roundToInt().coerceIn(0, length)
        return paragraph to offset
    }
}

/** Chapter progress for a paragraph and character offset; the inverse of [Book.position]. */
fun chapterProgressOf(paragraphs: List<String>, paragraph: Int, offset: Int): Float {
    if (paragraphs.isEmpty() || paragraph < 0) return 0f
    val index = paragraph.coerceIn(0, paragraphs.lastIndex)
    val length = paragraphs[index].length.coerceAtLeast(1)
    return ((index + offset.coerceIn(0, length).toFloat() / length) / paragraphs.size).coerceIn(0f, 1f)
}
