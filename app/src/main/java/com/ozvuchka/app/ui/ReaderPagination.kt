package com.ozvuchka.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ozvuchka.app.R

/** A page remembers the start of its text so a new font size can restore the reading position. */
internal data class ReaderPage(
    val blocks: List<ReaderBlock>,
    val startsAt: Float,
    val hasHeading: Boolean,
    /** Characters on the page; used for reading-time estimates. */
    val characters: Int,
)

/**
 * The original text coordinates survive line and page splitting for narration highlighting.
 * A block cut by a page break keeps the rest of its paragraph and shows only [maxLines] lines,
 * so its last visible line stays justified and a word broken by hyphenation keeps its hyphen.
 */
internal data class ReaderBlock(
    val text: String,
    val paragraphIndex: Int,
    val startOffset: Int,
    /** First line of a paragraph: it gets the first-line indent. */
    val paragraphStart: Boolean,
    val visibleLength: Int,
    val maxLines: Int,
    /** Space above the block in pixels, exactly as measured during pagination. */
    val gapPx: Int,
)

internal const val SCENE_BREAK = "✦  ✦  ✦"

// Literata is one variable font; each weight is an instance of its «wght» axis.
@OptIn(ExperimentalTextApi::class)
private val literata = FontFamily(
    listOf(400 to FontWeight.Normal, 500 to FontWeight.Medium, 600 to FontWeight.SemiBold, 700 to FontWeight.Bold).map { (weight, fontWeight) ->
        Font(R.font.literata, fontWeight, variationSettings = FontVariation.Settings(FontVariation.weight(weight)))
    },
)
private val ptSerif = FontFamily(
    Font(R.font.pt_serif_regular, FontWeight.Normal),
    Font(R.font.pt_serif_bold, FontWeight.Bold),
)

internal val ReaderFont.family: FontFamily
    get() = when (this) {
        ReaderFont.LITERATA -> literata
        ReaderFont.PT_SERIF -> ptSerif
        ReaderFont.SERIF -> FontFamily.Serif
        ReaderFont.SANS -> FontFamily.SansSerif
    }

internal fun readerBodyStyle(
    typography: ReaderTypography,
    language: String,
    paragraphStart: Boolean = true,
    centered: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = typography.font.family,
    fontSize = typography.fontSizeSp.sp,
    lineHeight = (typography.fontSizeSp * typography.lineSpacing).sp,
    textAlign = when {
        centered -> TextAlign.Center
        typography.justify -> TextAlign.Justify
        else -> TextAlign.Start
    },
    // Justified Russian text without hyphenation leaves wide gaps between long words.
    hyphens = if (typography.justify) Hyphens.Auto else Hyphens.None,
    lineBreak = LineBreak.Paragraph,
    localeList = LocaleList(if (language == "en") "en" else "ru"),
    textIndent = if (typography.paragraphIndent && paragraphStart && !centered) {
        TextIndent(firstLine = (typography.fontSizeSp * 1.5f).sp)
    } else {
        TextIndent.None
    },
)

internal fun readerTitleStyle(fontSizeSp: Float) = TextStyle(
    fontFamily = FontFamily.Serif,
    fontSize = (fontSizeSp + 9f).sp,
    lineHeight = (fontSizeSp + 14f).sp,
    fontWeight = FontWeight.Medium,
    hyphens = Hyphens.Auto,
)

internal val readerLabelStyle = TextStyle(
    fontSize = 11.sp,
    fontWeight = FontWeight.Bold,
    letterSpacing = 2.sp,
)

/** Vertical rhythm shared by pagination and rendering, so measured pages never clip. */
internal object ReaderRhythm {
    val headingGap = 12.dp
    val dividerHeight = 2.dp
    val afterHeading = 26.dp

    fun paragraphGapPx(typography: ReaderTypography, density: Density): Int = with(density) {
        val factor = if (typography.paragraphIndent) 0.35f else 0.9f
        (typography.fontSizeSp * factor).sp.roundToPx()
    }
}

internal fun chapterLabel(chapterIndex: Int, chapterCount: Int) = "ГЛАВА ${chapterIndex + 1}  /  $chapterCount"

internal fun paginateChapter(
    paragraphs: List<String>,
    chapterTitle: String,
    chapterLabel: String,
    typography: ReaderTypography,
    language: String,
    widthPx: Int,
    heightPx: Int,
    density: Density,
    measurer: TextMeasurer,
): List<ReaderPage> {
    val constraints = Constraints(maxWidth = widthPx.coerceAtLeast(1))
    val available = heightPx.coerceAtLeast(1)
    val paragraphGap = ReaderRhythm.paragraphGapPx(typography, density)
    val headingHeight = with(density) {
        measurer.measure(chapterLabel, readerLabelStyle, constraints = constraints).size.height +
            ReaderRhythm.headingGap.roundToPx() +
            measurer.measure(chapterTitle, readerTitleStyle(typography.fontSizeSp), constraints = constraints).size.height +
            ReaderRhythm.headingGap.roundToPx() +
            ReaderRhythm.dividerHeight.roundToPx() +
            ReaderRhythm.afterHeading.roundToPx()
    }
    // A sliver of tolerance for rounding between measured and drawn line boxes.
    val limit = available - with(density) { 2.dp.roundToPx() }

    val result = mutableListOf<ReaderPage>()
    var blocks = mutableListOf<ReaderBlock>()
    var showHeading = true
    var usedHeight = headingHeight
    var pageStart = 0f
    var pageCharacters = 0
    val paragraphCount = paragraphs.size.coerceAtLeast(1)

    fun position(paragraph: Int, offset: Int): Float {
        val length = paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
        return ((paragraph + offset.toFloat() / length) / paragraphCount).coerceIn(0f, 1f)
    }

    fun nextPage(paragraph: Int, offset: Int) {
        if (blocks.isNotEmpty() || showHeading) {
            result += ReaderPage(blocks.toList(), pageStart, showHeading, pageCharacters)
        }
        blocks = mutableListOf()
        showHeading = false
        usedHeight = 0
        pageCharacters = 0
        pageStart = position(paragraph, offset)
    }

    paragraphs.forEachIndexed { paragraphIndex, source ->
        val sceneBreak = source.isBlank()
        val original = if (sceneBreak) SCENE_BREAK else source.trim()
        var remaining = original
        var offset = if (sceneBreak) 0 else source.length - source.trimStart().length
        var paragraphStart = true
        while (remaining.isNotEmpty()) {
            val gap = if (blocks.isNotEmpty()) paragraphGap else 0
            val style = readerBodyStyle(typography, language, paragraphStart, centered = sceneBreak)
            val layout = measurer.measure(remaining, style, constraints = constraints)
            if (usedHeight + gap + layout.size.height <= limit) {
                blocks += ReaderBlock(
                    remaining, paragraphIndex, offset, paragraphStart,
                    visibleLength = remaining.length, maxLines = Int.MAX_VALUE, gapPx = gap,
                )
                usedHeight += gap + layout.size.height
                pageCharacters += remaining.length
                break
            }
            val spaceForText = limit - usedHeight - gap
            val fittingLines = (0 until layout.lineCount)
                .takeWhile { layout.getLineBottom(it) <= spaceForText }
                .size
            if (fittingLines == 0 && (blocks.isNotEmpty() || showHeading)) {
                nextPage(paragraphIndex, offset)
                continue
            }
            val lines = fittingLines.coerceAtLeast(1)
            val splitAt = layout.getLineEnd(lines - 1, visibleEnd = true).coerceIn(1, remaining.length)
            blocks += ReaderBlock(
                remaining, paragraphIndex, offset, paragraphStart,
                visibleLength = splitAt, maxLines = lines, gapPx = gap,
            )
            pageCharacters += splitAt
            val after = remaining.substring(splitAt)
            val trimmed = after.trimStart()
            offset += splitAt + (after.length - trimmed.length)
            remaining = trimmed
            paragraphStart = false
            if (remaining.isNotEmpty()) nextPage(paragraphIndex, offset)
        }
    }
    if (blocks.isNotEmpty() || showHeading || result.isEmpty()) {
        result += ReaderPage(blocks.toList(), pageStart, showHeading, pageCharacters)
    }
    return result
}
