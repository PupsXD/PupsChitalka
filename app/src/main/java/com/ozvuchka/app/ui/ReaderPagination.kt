package com.ozvuchka.app.ui

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A page remembers the start of its text so a new font size can restore the reading position. */
internal data class ReaderPage(
    val blocks: List<ReaderBlock>,
    val startsAt: Float,
    val hasHeading: Boolean,
)

/** The original text coordinates survive line and page splitting for narration highlighting. */
internal data class ReaderBlock(
    val text: String,
    val paragraphIndex: Int,
    val startOffset: Int,
)

internal fun readerBodyStyle(fontSizeSp: Float, useSerif: Boolean) = TextStyle(
    fontFamily = if (useSerif) FontFamily.Serif else FontFamily.SansSerif,
    fontSize = fontSizeSp.sp,
    lineHeight = (fontSizeSp * 1.63f).sp,
)

internal fun readerTitleStyle(fontSizeSp: Float) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = (fontSizeSp + 11f).sp,
    lineHeight = (fontSizeSp + 15f).sp,
    fontWeight = FontWeight.Medium,
)

internal val readerLabelStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 2.sp,
)

internal fun paginateChapter(
    paragraphs: List<String>,
    chapterTitle: String,
    fontSizeSp: Float,
    useSerif: Boolean,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    measurer: TextMeasurer,
): List<ReaderPage> {
    val safeWidth = widthPx.coerceAtLeast(1)
    val safeHeight = heightPx.coerceAtLeast(1)
    val constraints = Constraints(maxWidth = safeWidth)
    val bodyStyle = readerBodyStyle(fontSizeSp, useSerif)
    val titleStyle = readerTitleStyle(fontSizeSp)
    val spacing = with(density) { 20.dp.roundToPx() }
    val headingGap = with(density) { 14.dp.roundToPx() }
    val headingHeight = measurer.measure("ГЛАВА 1  /  1", readerLabelStyle, constraints = constraints).size.height +
        measurer.measure(chapterTitle, titleStyle, constraints = constraints).size.height +
        2 * headingGap + with(density) { 2.dp.roundToPx() }
    // Leave a full line of tolerance for font metrics and display scaling. No text may be clipped.
    val lineTolerance = measurer.measure("А", bodyStyle, constraints = constraints).size.height
    val availableHeight = safeHeight - with(density) { 62.dp.roundToPx() } - lineTolerance

    val result = mutableListOf<ReaderPage>()
    var blocks = mutableListOf<ReaderBlock>()
    var showHeading = true
    var usedHeight = headingHeight
    var pageStart = 0f
    val paragraphCount = paragraphs.size.coerceAtLeast(1)

    fun position(paragraph: Int, offset: Int): Float {
        val length = paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
        return ((paragraph + offset.toFloat() / length) / paragraphCount).coerceIn(0f, 1f)
    }

    fun nextPage(paragraph: Int, offset: Int) {
        if (blocks.isNotEmpty() || showHeading) {
            result += ReaderPage(blocks.toList(), pageStart, showHeading)
        }
        blocks = mutableListOf()
        showHeading = false
        usedHeight = 0
        pageStart = position(paragraph, offset)
    }

    paragraphs.forEachIndexed { paragraphIndex, source ->
        val original = source.trim().ifEmpty { "✦  ✦  ✦" }
        var remaining = original
        var offset = if (source.isBlank()) 0 else source.length - source.trimStart().length
        while (remaining.isNotEmpty()) {
            val gap = if (blocks.isNotEmpty() || showHeading) spacing else 0
            val layout = measurer.measure(remaining, bodyStyle, constraints = constraints)
            if (usedHeight + gap + layout.size.height <= availableHeight) {
                blocks += ReaderBlock(remaining, paragraphIndex, offset)
                usedHeight += gap + layout.size.height
                break
            }
            val spaceForText = availableHeight - usedHeight - gap
            val fittingLines = (0 until layout.lineCount)
                .takeWhile { layout.getLineBottom(it) <= spaceForText }
                .size
            if (fittingLines == 0 && (blocks.isNotEmpty() || showHeading)) {
                nextPage(paragraphIndex, offset)
                continue
            }
            val lines = fittingLines.coerceAtLeast(1)
            val splitAt = layout.getLineEnd(lines - 1, visibleEnd = true)
                .coerceIn(1, remaining.length)
            val piece = remaining.substring(0, splitAt).trimEnd()
            blocks += ReaderBlock(piece, paragraphIndex, offset)
            val after = remaining.substring(splitAt)
            val trimmed = after.trimStart()
            offset += splitAt + (after.length - trimmed.length)
            remaining = trimmed
            if (remaining.isNotEmpty()) nextPage(paragraphIndex, offset)
        }
    }
    if (blocks.isNotEmpty() || showHeading || result.isEmpty()) {
        result += ReaderPage(blocks.toList(), pageStart, showHeading)
    }
    return result
}
