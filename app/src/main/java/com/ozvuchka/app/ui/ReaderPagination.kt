package com.ozvuchka.app.ui

import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
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
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle

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
    /** Headings, notes, captions, pictures and table rows; null for running text. */
    val style: ParagraphStyle? = null,
    /** The size of a picture or a table row as laid out, in pixels. */
    val widthPx: Int = 0,
    val heightPx: Int = 0,
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

/** Text styles of headings, notes, captions and table cells, sized from the reader's font size. */
internal fun readerStyleFor(
    kind: ParagraphKind?,
    typography: ReaderTypography,
    language: String,
    paragraphStart: Boolean = true,
    centered: Boolean = false,
): TextStyle {
    val size = typography.fontSizeSp
    val locale = LocaleList(if (language == "en") "en" else "ru")
    fun plain(scale: Float, lineScale: Float, weight: FontWeight = FontWeight.Normal, italic: Boolean = false) = TextStyle(
        fontFamily = typography.font.family,
        fontSize = (size * scale).sp,
        lineHeight = (size * scale * lineScale).sp,
        fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal,
        textAlign = TextAlign.Start,
        hyphens = Hyphens.Auto,
        lineBreak = LineBreak.Paragraph,
        localeList = locale,
    )
    return when (kind) {
        ParagraphKind.HEADING -> plain(1.28f, 1.3f, FontWeight.Bold).copy(lineBreak = LineBreak.Heading)
        ParagraphKind.SUBHEADING -> plain(1.1f, 1.35f, FontWeight.SemiBold).copy(lineBreak = LineBreak.Heading)
        ParagraphKind.NOTE -> plain(0.88f, typography.lineSpacing)
        ParagraphKind.CAPTION -> plain(0.82f, 1.4f, italic = true)
        ParagraphKind.TABLE_ROW -> plain(0.8f, 1.35f).copy(hyphens = Hyphens.None)
        ParagraphKind.TABLE_HEADER -> plain(0.8f, 1.35f, FontWeight.SemiBold).copy(hyphens = Hyphens.None)
        else -> readerBodyStyle(typography, language, paragraphStart, centered)
    }
}

/** Space around blocks that are not running text. */
internal object ReaderInsets {
    /** A note is set off by a bar on its left and a tint. */
    val noteStart = 14.dp
    val noteEnd = 10.dp
    val noteVertical = 6.dp

    /** Padding inside a table cell. */
    val cellHorizontal = 5.dp
    val cellVertical = 4.dp
}

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

/**
 * Lays a chapter out in pages of [heightPx]. Running text breaks between lines; pictures, table
 * rows and headings move to the next page whole, a heading never ends a page, and a picture taller
 * than the page is scaled to fit. [maxPages] stops early, for a look at a chapter's first page.
 */
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
    styles: Map<Int, ParagraphStyle> = emptyMap(),
    maxPages: Int = Int.MAX_VALUE,
): List<ReaderPage> {
    val width = widthPx.coerceAtLeast(1)
    val constraints = Constraints(maxWidth = width)
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
    val limit = (available - with(density) { 2.dp.roundToPx() }).coerceAtLeast(1)
    val noteHorizontal = with(density) { (ReaderInsets.noteStart + ReaderInsets.noteEnd).roundToPx() }
    val noteVertical = with(density) { (ReaderInsets.noteVertical * 2).roundToPx() }
    val cellHorizontal = with(density) { (ReaderInsets.cellHorizontal * 2).roundToPx() }
    val cellVertical = with(density) { (ReaderInsets.cellVertical * 2).roundToPx() }
    val bodyLine = with(density) { (typography.fontSizeSp * typography.lineSpacing).sp.roundToPx() }
    val hairline = with(density) { 1.dp.roundToPx() }

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

    fun gapBefore(kind: ParagraphKind?): Int {
        if (blocks.isEmpty()) return 0
        val previous = blocks.last().style?.kind
        return when {
            kind == ParagraphKind.HEADING -> paragraphGap * 2 + bodyLine / 2
            kind == ParagraphKind.SUBHEADING -> paragraphGap + bodyLine / 2
            kind == ParagraphKind.IMAGE || previous == ParagraphKind.IMAGE -> maxOf(paragraphGap, bodyLine / 2)
            (kind == ParagraphKind.TABLE_ROW || kind == ParagraphKind.TABLE_HEADER) &&
                (previous == ParagraphKind.TABLE_ROW || previous == ParagraphKind.TABLE_HEADER) -> 0
            kind == ParagraphKind.CAPTION && previous == ParagraphKind.IMAGE -> paragraphGap / 2
            else -> paragraphGap
        }
    }

    for ((paragraphIndex, source) in paragraphs.withIndex()) {
        if (result.size >= maxPages) break
        val style = styles[paragraphIndex]
        val kind = style?.kind
        when (kind) {
            ParagraphKind.IMAGE -> {
                if (style.image == null || style.width <= 0 || style.height <= 0) continue
                // As wide as the column at most; small pictures keep their size.
                var pictureWidth = minOf(width.toFloat(), style.width * density.density)
                var pictureHeight = pictureWidth * style.height / style.width
                if (pictureHeight > limit) {
                    pictureWidth *= limit / pictureHeight
                    pictureHeight = limit.toFloat()
                }
                var gap = gapBefore(kind)
                val room = limit - usedHeight - gap
                if (pictureHeight > room) {
                    // A little smaller still reads well; much smaller goes to the next page.
                    if (room >= pictureHeight * 0.7f && room >= limit * 0.3f) {
                        pictureWidth *= room / pictureHeight
                        pictureHeight = room.toFloat()
                    } else if (blocks.isNotEmpty() || showHeading) {
                        nextPage(paragraphIndex, 0)
                        gap = 0
                    }
                }
                val heightOnPage = pictureHeight.toInt().coerceIn(1, limit)
                blocks += ReaderBlock(
                    "", paragraphIndex, 0, paragraphStart = true, visibleLength = 0, maxLines = 1, gapPx = gap,
                    style = style, widthPx = pictureWidth.toInt().coerceAtLeast(1), heightPx = heightOnPage,
                )
                usedHeight += gap + heightOnPage
            }
            ParagraphKind.TABLE_ROW, ParagraphKind.TABLE_HEADER -> {
                val cells = source.split('\t')
                // Each cell loses its padding and a hairline border to its neighbour.
                val cellWidth = (width / cells.size.coerceAtLeast(1) - cellHorizontal - hairline).coerceAtLeast(1)
                val cellStyle = readerStyleFor(kind, typography, language)
                val rowHeight = cells.maxOf { cell ->
                    measurer.measure(cell.ifEmpty { " " }, cellStyle, constraints = Constraints(maxWidth = cellWidth)).size.height
                } + cellVertical
                var gap = gapBefore(kind)
                if (usedHeight + gap + rowHeight > limit && (blocks.isNotEmpty() || showHeading)) {
                    nextPage(paragraphIndex, 0)
                    gap = 0
                }
                val heightOnPage = rowHeight.coerceAtMost(limit)
                blocks += ReaderBlock(
                    source, paragraphIndex, 0, paragraphStart = true, visibleLength = source.length, maxLines = Int.MAX_VALUE,
                    gapPx = gap, style = style, widthPx = width, heightPx = heightOnPage,
                )
                usedHeight += gap + heightOnPage
                pageCharacters += source.length
            }
            else -> {
                val sceneBreak = source.isBlank() && style == null
                if (source.isBlank() && !sceneBreak) continue
                val original = if (sceneBreak) SCENE_BREAK else source.trim()
                var remaining = original
                var offset = if (sceneBreak) 0 else source.length - source.trimStart().length
                var paragraphStart = true
                val inset = if (kind == ParagraphKind.NOTE) noteHorizontal else 0
                val padding = if (kind == ParagraphKind.NOTE) noteVertical else 0
                val textConstraints = if (inset > 0) Constraints(maxWidth = (width - inset).coerceAtLeast(1)) else constraints
                val heading = kind == ParagraphKind.HEADING || kind == ParagraphKind.SUBHEADING
                while (remaining.isNotEmpty()) {
                    var gap = if (paragraphStart) gapBefore(kind) else if (blocks.isNotEmpty()) paragraphGap else 0
                    val textStyle = readerStyleFor(kind, typography, language, paragraphStart, centered = sceneBreak)
                    val layout = measurer.measure(remaining, textStyle, constraints = textConstraints)
                    val blockHeight = layout.size.height + padding
                    // A heading keeps at least two lines of its section below it on the page.
                    val needed = if (heading) blockHeight + paragraphGap + bodyLine * 2 else blockHeight
                    if (usedHeight + gap + needed <= limit || (heading && blocks.isEmpty() && !showHeading)) {
                        blocks += ReaderBlock(
                            remaining, paragraphIndex, offset, paragraphStart,
                            visibleLength = remaining.length, maxLines = Int.MAX_VALUE, gapPx = gap, style = style,
                        )
                        usedHeight += gap + blockHeight
                        pageCharacters += remaining.length
                        break
                    }
                    if (heading) {
                        nextPage(paragraphIndex, offset)
                        continue
                    }
                    val spaceForText = limit - usedHeight - gap - padding
                    val fittingLines = (0 until layout.lineCount)
                        .takeWhile { layout.getLineBottom(it) <= spaceForText }
                        .size
                    if (fittingLines == 0 && (blocks.isNotEmpty() || showHeading)) {
                        nextPage(paragraphIndex, offset)
                        continue
                    }
                    val lines = fittingLines.coerceAtLeast(1)
                    val splitAt = layout.getLineEnd(lines - 1, visibleEnd = true).coerceIn(1, remaining.length)
                    if (blocks.isEmpty()) gap = 0
                    blocks += ReaderBlock(
                        remaining, paragraphIndex, offset, paragraphStart,
                        visibleLength = splitAt, maxLines = lines, gapPx = gap, style = style,
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
        }
    }
    if (result.size < maxPages && (blocks.isNotEmpty() || showHeading || result.isEmpty())) {
        result += ReaderPage(blocks.toList(), pageStart, showHeading, pageCharacters)
    }
    return result
}
