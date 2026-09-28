package com.ozvuchka.app.importer.pdf

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/*
 * Reading order for PDF books. A PDF only says where each letter is drawn, so this file rebuilds
 * what a reader sees: lines, the main text column, notes in the margin, pictures and tables with
 * their captions, running headers, headings and paragraphs that go on from page to page.
 *
 * It depends on nothing Android or PDF-library specific: an adapter feeds it glyphs and shapes
 * per page, so the same code runs in unit tests.
 */

/** A rectangle in points, measured from the top-left corner of the visible page. */
internal data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = max(0f, width) * max(0f, height)
    val isEmpty: Boolean get() = right <= left || bottom <= top

    fun union(other: PdfRect) = PdfRect(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
    fun inflate(by: Float) = PdfRect(left - by, top - by, right + by, bottom + by)
    fun intersect(other: PdfRect) = PdfRect(max(left, other.left), max(top, other.top), min(right, other.right), min(bottom, other.bottom))
    fun intersects(other: PdfRect) = left < other.right && other.left < right && top < other.bottom && other.top < bottom
    fun xOverlap(other: PdfRect) = min(right, other.right) - max(left, other.left)
    fun yOverlap(other: PdfRect) = min(bottom, other.bottom) - max(top, other.top)

    /** Share of this rectangle that lies inside [other]. */
    fun fractionInside(other: PdfRect): Float {
        val w = xOverlap(other)
        val h = yOverlap(other)
        if (w <= 0f || h <= 0f) return 0f
        return w * h / max(area, 0.01f)
    }

    /** Gap between the two rectangles, 0 when they touch or overlap. */
    fun distanceTo(other: PdfRect): Float {
        val dx = max(0f, max(other.left - right, left - other.right))
        val dy = max(0f, max(other.top - bottom, top - other.bottom))
        return max(dx, dy)
    }
}

/** One drawn character. [baseline] and [x] are in points from the top-left of the visible page. */
internal class PdfGlyph(
    val text: String,
    val x: Float,
    val baseline: Float,
    val width: Float,
    val size: Float,
    val font: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    /** Drawn without ink, like the recognized text a scanner lays over a page image. */
    val invisible: Boolean = false,
) {
    val right: Float get() = x + width
    val isSpace: Boolean get() = text.isBlank()
}

internal enum class ShapeKind { IMAGE, PATH }

/** Something drawn that is not text, by its visible bounds. */
internal class PdfShape(val bounds: PdfRect, val kind: ShapeKind)

/** Everything the adapter found on one page. */
internal class PdfPageInput(
    val index: Int,
    val width: Float,
    val height: Float,
    val glyphs: List<PdfGlyph>,
    val shapes: List<PdfShape>,
    /** Glyphs drawn in a font with no letters behind them: text that only OCR can read. */
    val unreadableGlyphs: Int = 0,
)

/** A chapter start from the PDF's own table of contents (bookmarks). */
internal class PdfOutlineEntry(val title: String, val level: Int, val page: Int, val top: Float = 0f)

internal enum class PdfItemKind { BODY, HEADING, SUBHEADING, NOTE, CAPTION, FIGURE }

/** A paragraph, heading or picture of the book, where it starts in the PDF. */
internal class PdfItem(
    val kind: PdfItemKind,
    val text: String,
    val page: Int,
    val top: Float,
    /** The region to render as a picture, for [PdfItemKind.FIGURE]. */
    val region: PdfRect? = null,
)

internal class PdfChapter(val title: String, val items: List<PdfItem>)

// ---------------------------------------------------------------- lines

/** A line of text as a reader sees it: one column's worth, never across a gutter. */
internal class PdfLine(
    val page: Int,
    val text: String,
    val box: PdfRect,
    val baseline: Float,
    val size: Float,
    val font: String,
    val bold: Boolean,
    val italic: Boolean,
    val invisible: Boolean,
    /** Letters and digits, for statistics. */
    val weight: Int,
)

private const val RUN_GAP = 0.8f
private const val GUTTER_GAP = 2.2f

private class LineBuilder(first: PdfGlyph) {
    val glyphs = ArrayList<PdfGlyph>(48).apply { add(first) }
    var left = first.x
    var right = first.right
    var top = first.baseline - first.size * 0.75f
    var bottom = first.baseline + first.size * 0.22f
    var baseline = first.baseline
    var size = first.size

    fun lastGlyph() = glyphs.last()

    /** The next glyph of the content stream continues this line. */
    fun continuesWith(glyph: PdfGlyph): Boolean {
        val last = glyphs.last()
        val em = max(last.size, glyph.size)
        if (abs(glyph.baseline - last.baseline) > em * 0.2f) return false
        if (glyph.x < last.right - em * 0.35f) return false
        return glyph.x - last.right <= em * RUN_GAP
    }

    fun add(glyph: PdfGlyph) {
        glyphs += glyph
        left = min(left, glyph.x)
        right = max(right, glyph.right)
        top = min(top, glyph.baseline - glyph.size * 0.75f)
        bottom = max(bottom, glyph.baseline + glyph.size * 0.22f)
        if (!glyph.isSpace && glyph.size > size * 0.9f) {
            size = max(size, glyph.size)
            baseline = glyph.baseline
        }
    }

    fun addAll(other: LineBuilder) = other.glyphs.forEach(::add)

    fun verticalOverlap(other: LineBuilder): Float =
        (min(bottom, other.bottom) - max(top, other.top)) / max(0.1f, min(bottom - top, other.bottom - other.top))

    fun gapTo(other: LineBuilder): Float = max(other.left - right, left - other.right)
}

/** Glyphs of one page in content order → lines, split where a column gutter runs between them. */
internal fun buildLines(page: PdfPageInput, offPage: MutableCollection<String>? = null): List<PdfLine> {
    val visible = PdfRect(0f, 0f, page.width, page.height)
    val runs = ArrayList<LineBuilder>()
    var run: LineBuilder? = null
    val hidden = StringBuilder()
    for (glyph in page.glyphs) {
        if (glyph.size < 1f || glyph.text.isEmpty()) continue
        // Watermarks and crop marks often sit outside the visible page.
        if (glyph.right < visible.left - 1f || glyph.x > visible.right + 1f ||
            glyph.baseline < visible.top || glyph.baseline - glyph.size * 0.6f > visible.bottom
        ) {
            hidden.append(glyph.text)
            continue
        } else if (hidden.isNotEmpty()) {
            offPage?.add(hidden.toString().trim())
            hidden.clear()
        }
        val current = run
        run = if (current != null && current.continuesWith(glyph)) {
            current.add(glyph)
            current
        } else {
            LineBuilder(glyph).also(runs::add)
        }
    }
    if (hidden.isNotEmpty()) offPage?.add(hidden.toString().trim())
    // Runs that share a baseline and nearly touch are one line, whatever order they were drawn in.
    runs.sortWith(compareBy({ it.baseline }, { it.left }))
    val lines = ArrayList<LineBuilder>()
    for (piece in runs) {
        if (piece.glyphs.all { it.isSpace }) continue
        var target: LineBuilder? = null
        for (i in lines.indices.reversed()) {
            val line = lines[i]
            if (piece.baseline - line.baseline > max(piece.size, line.size) * 1.5f) break
            val em = max(piece.size, line.size)
            if (piece.verticalOverlap(line) < 0.5f) continue
            val gap = piece.gapTo(line)
            val beside = gap <= em * RUN_GAP && gap > -em * 0.5f
            // An index or an exponent drawn after the line around it: «L» «t+1» «= L» «t».
            val inside = gap <= 0f && piece.size <= line.size * 0.85f && line.glyphs.none { glyph ->
                !glyph.isSpace && min(glyph.right, piece.right) - max(glyph.x, piece.left) > (piece.right - piece.left) * 0.3f
            }
            if (beside || inside) {
                target = line
                break
            }
        }
        if (target != null) target.addAll(piece) else lines += piece
    }
    joinAcrossWordGaps(lines)
    return lines.mapNotNull { finishLine(page.index, it) }
}

/**
 * Wide gaps on one baseline are either a very loose line or a gutter between columns. A gutter is
 * an empty vertical stripe: no line above or below crosses it.
 */
private fun joinAcrossWordGaps(lines: MutableList<LineBuilder>) {
    var changed = true
    while (changed) {
        changed = false
        loop@ for (i in lines.indices) {
            val a = lines[i]
            for (j in lines.indices) {
                if (i == j) continue
                val b = lines[j]
                if (b.left < a.right) continue
                val em = max(a.size, b.size)
                if (abs(a.baseline - b.baseline) > em * 0.25f) continue
                val gap = b.left - a.right
                if (gap > em * GUTTER_GAP) continue
                if (abs(a.size - b.size) > em * 0.15f) continue
                val middle = (a.right + b.left) / 2f
                // Close pieces are one line (an index inserted between them narrowed the gap), and so
                // are a list marker and its item, however far the item is indented.
                val crossed = gap <= em * RUN_GAP || (gap <= em * 4f && isListMarker(a)) || lines.any { other ->
                    other !== a && other !== b && abs(other.baseline - a.baseline) < em * 5f &&
                        other.left < middle - 1f && other.right > middle + 1f
                }
                if (crossed) {
                    a.addAll(b)
                    lines.removeAt(j)
                    changed = true
                    break@loop
                }
            }
        }
    }
}

/** «•», «■», «1.», «a)»: the marker of a list item, drawn apart from the item's text. */
private fun isListMarker(line: LineBuilder): Boolean {
    val inked = line.glyphs.filter { !it.isSpace }
    if (inked.size == 1 && (inked[0].text.firstOrNull() in bulletGlyphs || isDingbatFont(inked[0].font))) return true
    val text = inked.joinToString("") { it.text }
    return text.length <= 3 && Regex("^(?:\\d{1,2}|[a-zA-Zа-яА-Я])[.)]$").matches(text)
}

private val ligatures = mapOf(
    'ﬀ' to "ff", 'ﬁ' to "fi", 'ﬂ' to "fl", 'ﬃ' to "ffi", 'ﬄ' to "ffl",
    'ﬅ' to "st", 'ﬆ' to "st",
)

private val bulletGlyphs = setOf('■', '●', '•', '▪', '◆', '◼', '►', '▸', '❑', '□', '○', '◦', '‣', '⁃', '∙', '·')

internal fun cleanGlyphText(text: String): String {
    if (text.length == 1) {
        val c = text[0]
        ligatures[c]?.let { return it }
        return when {
            c == ' ' || c in ' '..' ' || c == ' ' -> " "
            c == '�' || c < ' ' || c in ''..'' -> ""
            else -> text
        }
    }
    val result = StringBuilder(text.length)
    for (c in text) {
        val ligature = ligatures[c]
        when {
            ligature != null -> result.append(ligature)
            c == ' ' || c in ' '..' ' || c == ' ' -> result.append(' ')
            c == '�' || c < ' ' || c in ''..'' -> Unit
            else -> result.append(c)
        }
    }
    return result.toString()
}

private fun finishLine(pageIndex: Int, builder: LineBuilder): PdfLine? {
    val glyphs = builder.glyphs.sortedBy { it.x }
    val text = StringBuilder()
    var previous: PdfGlyph? = null
    val fonts = HashMap<String, Int>()
    val sizes = ArrayList<Float>(glyphs.size)
    var bold = 0
    var italic = 0
    var invisible = 0
    var inked = 0
    var left = Float.MAX_VALUE
    var right = -Float.MAX_VALUE
    for (glyph in glyphs) {
        // Symbol fonts draw list bullets and ornaments; their letters mean nothing as text.
        val clean = if (isDingbatFont(glyph.font)) {
            if (inked == 0 && glyph.width > 0.5f) "•" else ""
        } else {
            cleanGlyphText(glyph.text)
        }
        if (clean.isBlank()) {
            if (text.isNotEmpty() && text.last() != ' ') text.append(' ')
            previous = glyph
            continue
        }
        val before = previous
        if (before != null && !before.isSpace && text.isNotEmpty() && text.last() != ' ') {
            val em = max(before.size, glyph.size)
            if (glyph.x - before.right > em * 0.18f) text.append(' ')
        }
        text.append(clean)
        previous = glyph
        inked++
        fonts.merge(glyph.font, clean.length, Int::plus)
        sizes += glyph.size
        if (glyph.bold) bold++
        if (glyph.italic) italic++
        if (glyph.invisible) invisible++
        left = min(left, glyph.x)
        right = max(right, glyph.right)
    }
    val value = text.toString().trim()
    if (inked == 0 || value.isEmpty()) return null
    sizes.sort()
    val size = sizes[sizes.size / 2]
    val font = fonts.maxByOrNull { it.value }!!.key
    return PdfLine(
        page = pageIndex,
        text = value,
        box = PdfRect(left, builder.top, max(right, left + 0.5f), builder.bottom),
        baseline = builder.baseline,
        size = size,
        font = font,
        bold = bold * 2 > inked || isBoldName(font),
        italic = italic * 2 > inked || isItalicName(font),
        invisible = invisible * 2 > inked,
        weight = value.count(Char::isLetterOrDigit),
    )
}

internal fun isDingbatFont(font: String): Boolean = Regex("(?i)dingbat|wingding|webding|zapf").containsMatchIn(font)

internal fun isBoldName(font: String): Boolean =
    Regex("(?i)bold|black|heavy|semibold|demi|[-,]bd\\b|extrabold").containsMatchIn(font)

internal fun isItalicName(font: String): Boolean = Regex("(?i)italic|oblique|[-,]it\\b").containsMatchIn(font)

// ---------------------------------------------------------------- shapes

/** Shapes drawn close together: a picture, a table grid, a framed box, a rule or an icon. */
internal class ShapeCluster(
    var box: PdfRect,
    val count: Int,
    val hasImage: Boolean,
    /** Only hairlines: rules, underlines, table borders without anything else. */
    val onlyRules: Boolean,
)

/** A line of running text rather than a label: long, and wide for the area it sits in. */
private fun isProse(line: PdfLine, area: PdfRect): Boolean = line.text.length >= 25 && line.box.width >= area.width * 0.4f

/**
 * Splits the page's shapes into frames and tints behind running text (sidebars, boxed notes, the
 * picture of a scanned page under its text layer) and clusters of everything else.
 */
internal fun sortShapes(page: PdfPageInput, lines: List<PdfLine>): Pair<List<PdfRect>, List<ShapeCluster>> {
    val visible = PdfRect(0f, 0f, page.width, page.height)
    val pageArea = visible.area
    val kept = ArrayList<PdfShape>()
    val frames = ArrayList<PdfRect>()
    for (shape in page.shapes) {
        val box = shape.bounds.intersect(visible)
        if (box.right - box.left < 0.1f && box.bottom - box.top < 0.1f) continue
        if (box.right < box.left || box.bottom < box.top) continue
        // Page backgrounds and full-page scans are not pictures of the text.
        if (box.area >= pageArea * 0.85f) continue
        // Elements that bleed off the page edge are decoration: tabs, bands, crop marks.
        val bleeds = shape.bounds.left < -0.5f || shape.bounds.top < -0.5f ||
            shape.bounds.right > page.width + 0.5f || shape.bounds.bottom > page.height + 0.5f ||
            box.left <= 0.5f || box.top <= 0.5f || box.right >= page.width - 0.5f || box.bottom >= page.height - 0.5f
        if (bleeds) continue
        if (box.width >= 40f && box.height >= 20f) {
            val inside = lines.filter { it.box.fractionInside(box.inflate(2f)) >= 0.9f }
            if (inside.count { isProse(it, box) } >= 2) {
                frames += box
                continue
            }
        }
        kept += PdfShape(box, shape.kind)
    }
    return frames to clusterShapes(kept)
}

private fun clusterShapes(kept: List<PdfShape>): List<ShapeCluster> {
    if (kept.isEmpty()) return emptyList()
    // Union-find over shapes whose surroundings touch.
    val parent = IntArray(kept.size) { it }
    fun find(i: Int): Int {
        var x = i
        while (parent[x] != x) {
            parent[x] = parent[parent[x]]
            x = parent[x]
        }
        return x
    }
    val reach = 6f
    val order = kept.indices.sortedBy { kept[it].bounds.top }
    // Sweep by top edge: only shapes that start within reach of another's bottom can touch it.
    val active = ArrayList<Int>()
    for (i in order) {
        val box = kept[i].bounds
        active.removeAll { kept[it].bounds.bottom + reach < box.top }
        for (j in active) {
            if (box.inflate(reach).intersects(kept[j].bounds)) {
                val a = find(i)
                val b = find(j)
                if (a != b) parent[a] = b
            }
        }
        active += i
        if (active.size > 3_000) active.removeAt(0)
    }
    val groups = kept.indices.groupBy(::find)
    return groups.values.map { members ->
        var box = kept[members[0]].bounds
        members.forEach { box = box.union(kept[it].bounds) }
        ShapeCluster(
            box = box,
            count = members.size,
            hasImage = members.any { kept[it].kind == ShapeKind.IMAGE },
            onlyRules = members.all { kept[it].bounds.width < 2.5f || kept[it].bounds.height < 2.5f },
        )
    }
}

/** The page's largest picture, as seen: a scan fills nearly all of the page. */
internal fun largestImage(page: PdfPageInput): PdfRect? {
    val visible = PdfRect(0f, 0f, page.width, page.height)
    return page.shapes.asSequence().filter { it.kind == ShapeKind.IMAGE }
        .map { it.bounds.intersect(visible) }
        .filter { !it.isEmpty }
        .maxByOrNull { it.area }
}

/** What one page contributes to the book, before the book-wide pass. */
internal class PdfPageLayout(
    val index: Int,
    val width: Float,
    val height: Float,
    val lines: List<PdfLine>,
    val clusters: List<ShapeCluster>,
    /** Frames and tints behind running text: boxed notes and sidebars. */
    val frames: List<PdfRect>,
    /** The largest picture on the page. */
    val largestImage: PdfRect?,
    /** Text drawn outside the visible page, such as a watermark with the buyer's order number. */
    val offPage: Set<String> = emptySet(),
    val unreadableGlyphs: Int = 0,
    /** Text recognized on a scanned page, already split into paragraphs. */
    var ocrParagraphs: List<String>? = null,
) {
    /** Share of the page covered by its largest picture. */
    val imageShare: Float get() = (largestImage?.area ?: 0f) / max(1f, width * height)
}

internal fun analyzePage(page: PdfPageInput): PdfPageLayout {
    val offPage = HashSet<String>()
    val lines = buildLines(page, offPage)
    val (frames, clusters) = sortShapes(page, lines)
    return PdfPageLayout(page.index, page.width, page.height, lines, clusters, frames, largestImage(page), offPage, page.unreadableGlyphs)
}

/** A picture of a page with little or no text layer over it. */
internal fun PdfPageLayout.looksScanned(): Boolean {
    val letters = lines.sumOf { it.weight }
    if (letters >= 200) return false
    return (imageShare >= 0.45f && letters < 120) || (letters < 20 && clusters.isEmpty() && imageShare > 0.1f)
}

/** Text in fonts that do not say which letters they draw: only its picture can be read. */
private fun PdfPageLayout.unreadable(): Boolean = unreadableGlyphs >= 150 && lines.sumOf { it.weight } < unreadableGlyphs / 4

/**
 * Pages for OCR: the scans of a scanned book, and pages whose text no font can name. In a book
 * with a text layer a full-page picture (the cover, a plate) is shown as a picture instead.
 */
internal fun pagesToRecognize(pages: List<PdfPageLayout>): List<PdfPageLayout> {
    val scans = pages.filter { it.looksScanned() }
    val scannedBook = scans.size >= maxOf(2, pages.size / 4) || (pages.size <= 3 && scans.isNotEmpty())
    return pages.filter { it.unreadable() || (scannedBook && it in scans) }
}

// ---------------------------------------------------------------- the book

private val captionStart = Regex(
    "^(?i:figure|fig\\.|table|tab\\.|chart|diagram|illustration|listing|рис\\.?|рисунок|таблица|табл\\.|схема|диаграмма)" +
        "\\s*[\\dA-ZА-Я]+(?:[.\\-–:][\\dA-Z]+)*\\b",
)
/** «Глава 3», «Chapter 3: Combat», «Part II», «Приложение А»: a chapter heading on a line of its own. */
private val chapterLine = Regex(
    "^((?i:глава|chapter|часть|part|книга|book|приложение|appendix)\\s+(?:\\d{1,3}|[ivxlcdm]{1,6}|[a-zа-я]))\\b(?:[.:—–-]?\\s.*)?$|" +
        "^((?i:пролог|эпилог|prologue|epilogue|предисловие|послесловие|введение|заключение))\\.?$",
)
private val sentenceEnds = setOf('.', '!', '?', '…', ':', '»', '"', '”', ')')
private val pageNumberOnly = Regex("^[#\\s\\-–—.|/]*$")
private val romanToken = Regex("\\b[ivxlcdm]{1,7}\\b")
private val dotLeader = Regex("(?:\\s*\\.){4,}\\s*|\\s*…{2,}\\s*|(?:\\s*·){4,}\\s*")
private val tocEntry = Regex(".*(?:\\.\\s*){3}\\S{0,6}$|.*…\\s*[\\dxivlcXIVLC]{1,6}$")
private val numberedItem = Regex("^(?:\\d{1,2}|[a-zа-я])[.)]\\s")

private class Block(val lines: MutableList<PdfLine>) {
    var box: PdfRect = lines.first().box
    val size: Float get() = lines.map { it.size }.sorted()[lines.size / 2]
    val page: Int get() = lines.first().page
    val text: String get() = lines.joinToString(" ") { it.text }

    fun add(line: PdfLine) {
        lines += line
        box = box.union(line.box)
    }
}

private enum class FloatKind { NOTE, CAPTION, FIGURE }

private class Floating(
    val kind: FloatKind,
    val page: Int,
    val top: Float,
    val blocks: List<Block> = emptyList(),
    val region: PdfRect? = null,
    val caption: Block? = null,
)

/**
 * The book-wide pass: finds what repeats on every page and what the text column is, then walks the
 * pages in reading order and builds chapters of paragraphs, headings, notes and pictures.
 */
internal class PdfBookLayout(
    private val pages: List<PdfPageLayout>,
    private val outline: List<PdfOutlineEntry>,
    private val bookTitle: String,
) {
    private val bodySize: Float
    private val bodyFont: String
    private val furniture = HashSet<PdfLine>()
    internal val brokenCaseFonts = HashSet<String>()
    private val columns = HashMap<Int, ClosedFloatingPointRange<Float>>()
    private val knownWords = HashSet<String>()

    /** How the book's own text writes a word mid-sentence: «dvd» → «DVD», «holopainen» → «Holopainen». */
    internal val spellings = HashMap<String, String>()

    init {
        val sizeWeights = HashMap<Float, Int>()
        val fontWeights = HashMap<String, Int>()
        for (page in pages) for (line in page.lines) {
            sizeWeights.merge((line.size * 4).toInt() / 4f, line.weight, Int::plus)
        }
        bodySize = sizeWeights.maxByOrNull { it.value }?.key ?: 10f
        for (page in pages) for (line in page.lines) {
            if (abs(line.size - bodySize) <= bodySize * 0.08f) fontWeights.merge(line.font, line.weight, Int::plus)
        }
        bodyFont = fontWeights.maxByOrNull { it.value }?.key.orEmpty()
        findFurniture()
        findColumns()
        findBrokenCaseFonts()
        val casings = HashMap<String, HashMap<String, Int>>()
        for (page in pages) for (line in page.lines) {
            if (line in furniture) continue
            // Words inside lines, never the last one, which may be cut by a hyphen.
            val words = line.text.split(' ')
            for (w in 0 until words.size - 1) {
                val word = words[w].trim(',', '.', ';', ':', '!', '?', '(', ')', '"', '“', '”', '«', '»', '’', '\'')
                if (word.length >= 3) knownWords += word.lowercase()
                // Mid-sentence words of trustworthy fonts show how names and abbreviations are written.
                if (w == 0 || line.font in brokenCaseFonts || word.length < 2 || !word.all { it.isLetter() }) continue
                if (words[w - 1].trimEnd('"', '”', '»', ')', '’').lastOrNull() in setOf('.', '!', '?', ':')) continue
                casings.getOrPut(word.lowercase()) { HashMap() }.merge(word, 1, Int::plus)
            }
        }
        for ((lower, forms) in casings) {
            val total = forms.values.sum()
            val (best, count) = forms.maxByOrNull { it.value } ?: continue
            if (best != lower && total >= 3 && count >= total * 0.8f) spellings[lower] = best
        }
    }

    /** Running headers, footers and page numbers: the same text in the same margin on many pages. */
    private fun findFurniture() {
        val counts = HashMap<String, HashSet<Int>>()
        fun key(line: PdfLine, page: PdfPageLayout): String? {
            val band = when {
                line.box.bottom <= page.height * 0.1f -> "top"
                line.box.top >= page.height * 0.9f -> "bottom"
                else -> return null
            }
            val normalized = line.text.lowercase()
                .replace(Regex("\\d+"), "#")
                .replace(romanToken, "#")
                .replace(Regex("\\s+"), " ")
                .trim()
            // Without page numbers, so «12 GAME MECHANICS» and «GAME MECHANICS 13» count as one header.
            return "$band|" + normalized.split(' ').filterNot { '#' in it }.joinToString(" ")
        }
        for (page in pages) for (line in page.lines) {
            val key = key(line, page) ?: continue
            counts.getOrPut(key) { HashSet() } += page.index
        }
        // Where running headers sit, in which font: a header naming the current section is set there
        // too, though its text repeats on fewer pages.
        fun slot(line: PdfLine, page: PdfPageLayout) =
            "${page.index % 2}|${(line.baseline / 3f).toInt()}|${line.font}|${(line.size * 2).toInt()}"
        val slots = HashMap<String, Int>()
        for (page in pages) for (line in page.lines) {
            val key = key(line, page) ?: continue
            val text = key.substringAfter('|')
            val repeated = text.isNotEmpty() && (counts[key]?.size ?: 0) >= 3
            val pageNumber = pageNumberOnly.matches(text) || text in setOf("page", "p.", "стр.", "стр", "страница", "с.")
            if ((repeated && line.size <= bodySize * 1.6f) || pageNumber) {
                furniture += line
                if (repeated) slots.merge(slot(line, page), 1, Int::plus)
            }
        }
        for (page in pages) for (line in page.lines) {
            if (line in furniture || key(line, page) == null) continue
            if ((slots[slot(line, page)] ?: 0) >= 5 && line.size <= bodySize * 1.6f) furniture += line
        }
        // A watermark drawn beside most pages is also noise where it lands on one.
        val hidden = HashMap<String, Int>()
        pages.forEach { page -> page.offPage.forEach { text -> if (text.length >= 4) hidden.merge(text, 1, Int::plus) } }
        val watermarks = hidden.filter { it.value >= 3 && it.value >= pages.size * 0.3f }.keys
        val blank = Regex("(?i)^(?:this page (?:is )?intentionally left blank|эта страница (?:специально|намеренно) оставлена пустой)\\.?$")
        for (page in pages) for (line in page.lines) {
            if (line.text in watermarks || blank.matches(line.text)) furniture += line
        }
    }

    /** The main text column of left and right pages: where most body lines start and end. */
    private fun findColumns() {
        fun column(samples: List<PdfLine>): ClosedFloatingPointRange<Float>? {
            if (samples.isEmpty()) return null
            val starts = HashMap<Int, Int>()
            samples.forEach { starts.merge(it.box.left.toInt() / 2, 1, Int::plus) }
            val left = (starts.maxByOrNull { it.value }!!.key * 2).toFloat()
            val rights = samples.filter { abs(it.box.left - left) <= 4f }.map { it.box.right }.sorted()
            if (rights.isEmpty()) return null
            return left..rights[(rights.size * 0.9f).toInt().coerceAtMost(rights.lastIndex)]
        }
        val bodyLines = pages.associate { page ->
            page.index to page.lines.filter { line ->
                line !in furniture && abs(line.size - bodySize) <= bodySize * 0.12f && line.text.length >= 25
            }
        }
        // Left and right pages may set the column apart; a short text is measured as a whole.
        for (parity in 0..1) {
            val samples = pages.filter { it.index % 2 == parity }.flatMap { bodyLines[it.index].orEmpty() }
            if (samples.size >= 20) column(samples)?.let { columns[parity] = it }
        }
        if (columns.size < 2) {
            val all = column(bodyLines.values.flatten().takeIf { it.size >= 3 }.orEmpty())
            for (parity in 0..1) {
                (columns[parity] ?: columns.values.firstOrNull() ?: all)?.let { columns[parity] = it }
            }
        }
    }

    private fun columnOf(page: PdfPageLayout): ClosedFloatingPointRange<Float> =
        columns[page.index % 2] ?: (page.width * 0.1f)..(page.width * 0.9f)

    /**
     * Some fonts map capital letters to small ones («however, we…» where the page shows «However»).
     * Such a font starts many sentences with a small letter.
     */
    private fun findBrokenCaseFonts() {
        val upper = HashMap<String, Int>()
        val lower = HashMap<String, Int>()
        val odd = HashMap<String, Int>()
        val words = HashMap<String, Int>()
        val sentenceStart = Regex("(?<=\\p{Ll}{3}[.!?]\\s)\\p{L}")
        val fonts = HashSet<String>()
        for (page in pages) for (line in page.lines) {
            fonts += line.font
            val family = fontFamily(line.font)
            sentenceStart.findAll(line.text).forEach { match ->
                if (match.value[0].isUpperCase()) upper.merge(family, 1, Int::plus) else lower.merge(family, 1, Int::plus)
            }
            for (word in line.text.split(' ')) {
                val letters = word.filter { it.isLetter() }
                if (letters.length < 2) continue
                words.merge(family, 1, Int::plus)
                if (isOddCase(letters)) odd.merge(family, 1, Int::plus)
            }
        }
        val broken = HashSet<String>()
        for (family in words.keys) {
            val small = lower[family] ?: 0
            val capital = upper[family] ?: 0
            val mixed = odd[family] ?: 0
            if ((small >= 3 && small >= (small + capital) * 0.2f) || (mixed >= 5 && mixed >= (words[family] ?: 0) * 0.01f)) broken += family
        }
        fonts.filterTo(brokenCaseFonts) { fontFamily(it) in broken }
    }

    private fun isHeadingLine(line: PdfLine): Boolean = line.size >= bodySize * 1.15f

    private fun headingKind(block: Block): PdfItemKind? {
        val size = block.size
        if (block.lines.size > 4 || block.text.length > 200) return null
        if (size >= bodySize * 1.15f && block.lines.all { isHeadingLine(it) }) {
            return if (size >= bodySize * 1.5f) PdfItemKind.HEADING else PdfItemKind.SUBHEADING
        }
        // A short bold line of its own, in the body size, as «Summary» or «Exercises».
        val bold = block.lines.all { it.bold } && block.lines.size <= 2 && block.text.length <= 80
        if (bold && size >= bodySize * 0.95f && block.text.last() !in ".,;" && block.lines.none { it.font == bodyFont && !it.bold }) {
            return PdfItemKind.SUBHEADING
        }
        return null
    }

    // ------------------------------------------------------------ one page

    private class PageFlow(val main: List<Block>, val anchored: Map<Int, List<Floating>>, val headings: Map<Block, PdfItemKind>)

    private fun pageFlow(page: PdfPageLayout): PageFlow {
        val column = columnOf(page)
        val columnWidth = (column.endInclusive - column.start).coerceAtLeast(page.width * 0.3f)
        val lines = page.lines.filter { it !in furniture }

        // Pictures and tables: shapes drawn close together, with the labels drawn on them. Parts of
        // one diagram (a row of nodes, arrows over their labels) join unless running text lies between.
        fun beside(box: PdfRect) = box.right <= column.start + 4f || box.left >= column.endInclusive - 4f
        val running = lines.filter { isRunningText(it, columnWidth) }
        val candidates = page.clusters
            .filter { !(it.box.width < 42f && it.box.height < 42f && beside(it.box)) }
            .map { Candidate(it.box, it.count, it.hasImage, it.onlyRules) }
            .toMutableList()
        joinCandidates(candidates, running, ::beside)
        val figures = ArrayList<PdfRect>()
        val textBoxes = ArrayList<PdfRect>()
        for (candidate in candidates) {
            val box = candidate.box
            if (box.width < 42f && box.height < 42f) continue
            val inside = lines.filter { it.box.fractionInside(box.inflate(3f)) >= 0.6f }
            if (candidate.onlyRules && (inside.isEmpty() || candidate.count <= 4)) continue
            val insideLetters = inside.sumOf { it.weight }
            val prose = inside.filter { isProse(it, box) && it in running }
            // Running text over a tint or between rules is a sidebar, not a picture.
            if (prose.size >= 3 && prose.sumOf { it.weight } >= insideLetters * 0.6f) {
                textBoxes += box
                continue
            }
            figures += box
        }
        // A page that is one big picture (the cover, a plate) and was not read by OCR is shown as it is.
        val picture = page.largestImage
        if (picture != null && page.looksScanned() && page.ocrParagraphs == null) figures += picture
        mergeOverlapping(figures)
        // A frame around running text is a boxed note, unless it is a cell of a table.
        for (frame in page.frames) {
            if (figures.none { frame.fractionInside(it.inflate(3f)) >= 0.9f }) textBoxes += frame
        }
        mergeOverlapping(textBoxes)

        val figureOf = HashMap<PdfLine, Int>()
        for (line in lines) {
            val index = figures.indexOfFirst { line.box.fractionInside(it.inflate(3f)) >= 0.6f }
            if (index >= 0) figureOf[line] = index
        }
        val textLines = lines.filter { it !in figureOf }

        // Blocks: lines stacked one under another in the same column, in the same size.
        val blocks = buildBlocks(textLines)

        // Short labels right next to a picture belong to it.
        val absorbed = HashSet<Block>()
        repeat(2) {
            for (block in blocks) {
                if (block in absorbed || isCaption(block)) continue
                val labelLike = block.lines.size <= 3 && block.lines.all { it.text.length <= 40 } &&
                    (block.lines.all { it.font != bodyFont } || block.size < bodySize * 0.92f)
                if (!labelLike) continue
                val index = figures.indexOfFirst { figure ->
                    val gap = block.box.distanceTo(figure)
                    gap <= 8f && (block.box.xOverlap(figure) > 0f || gap <= 3f)
                }
                if (index >= 0) {
                    figures[index] = figures[index].union(block.box)
                    absorbed += block
                }
            }
        }

        val floats = ArrayList<Floating>()
        val usedCaptions = HashSet<Block>()
        val mainBlocks = ArrayList<Block>()
        val sideBlocks = ArrayList<Block>()
        val boxed = HashMap<Int, MutableList<Block>>()
        for (block in blocks) {
            if (block in absorbed) continue
            // A column of bare numbers: the page numbers of a contents page, the remains of a table.
            val text = block.text
            if (text.none { it.isLetter() } && text.count { it.isDigit() } >= 8 && block.lines.size >= 3) continue
            val boxIndex = textBoxes.indexOfFirst { block.box.fractionInside(it.inflate(4f)) >= 0.5f }
            if (boxIndex >= 0) {
                boxed.getOrPut(boxIndex) { ArrayList() } += block
                continue
            }
            val outside = block.box.right <= column.start + 4f || block.box.left >= column.endInclusive - 4f
            if (outside && block.box.width <= columnWidth * 0.6f) sideBlocks += block else mainBlocks += block
        }

        // Captions go with the nearest picture, wherever they are set: beside it, under it, in its box.
        val captions = (sideBlocks + mainBlocks + boxed.values.flatten()).filter { isCaption(it) || isPlainCaption(it) }
        for (region in figures) {
            val caption = captions.filter { it !in usedCaptions }
                .minByOrNull { block ->
                    val vertical = if (block.box.yOverlap(region) > 0f) 0f else block.box.distanceTo(region)
                    vertical + abs(block.box.top - region.top) * 0.05f
                }
                ?.takeIf { it.box.distanceTo(region) <= 40f || (it.box.yOverlap(region) > 0f && isCaption(it)) }
            if (caption != null) usedCaptions += caption
            floats += Floating(FloatKind.FIGURE, page.index, region.top, region = region.inflate(4f).intersect(PdfRect(0f, 0f, page.width, page.height)), caption = caption)
        }
        mainBlocks.removeAll(usedCaptions)
        sideBlocks.removeAll(usedCaptions)
        boxed.values.forEach { it.removeAll(usedCaptions) }
        // A heading set in the margin beside the first line of its section goes before that section.
        val sideHeads = sideBlocks.filter { side ->
            headingKind(side) != null && mainBlocks.any { main ->
                val em = max(main.size, side.size)
                main.box.left >= side.box.left - 2f && main.box.top - side.box.top in -em..em * 2.2f
            }
        }
        sideBlocks.removeAll(sideHeads)
        mainBlocks += sideHeads
        // A caption whose picture was not found still reads as a caption, never as body text.
        val loneCaptions = mainBlocks.filter(::isCaption)
        mainBlocks.removeAll(loneCaptions)
        for (block in sideBlocks + loneCaptions) {
            floats += Floating(if (isCaption(block)) FloatKind.CAPTION else FloatKind.NOTE, page.index, block.box.top, listOf(block))
        }
        for ((_, members) in boxed) {
            if (members.isEmpty()) continue
            val ordered = members.sortedWith(compareBy({ it.box.top }, { it.box.left }))
            floats += Floating(FloatKind.NOTE, page.index, ordered.first().box.top, ordered)
        }

        val headings = HashMap<Block, PdfItemKind>()
        for (block in mainBlocks) headingKind(block)?.let { headings[block] = it }
        val ordered = readingOrder(mainBlocks)
        val anchored = HashMap<Int, MutableList<Floating>>()
        for (floating in floats.sortedBy { it.top }) {
            // After the text it stands next to: the last block above it in its own column, or on the page.
            val area = floating.region ?: floating.blocks.map { it.box }.reduce(PdfRect::union)
            val above = ordered.withIndex().filter { (_, block) -> block.box.top <= floating.top + 2f }
            val sameColumn = above.filter { (_, block) -> block.box.xOverlap(area) > 0f }
            val anchor = (sameColumn.ifEmpty { above }).maxByOrNull { (_, block) -> block.box.top }?.index ?: -1
            anchored.getOrPut(anchor) { ArrayList() } += floating
        }
        return PageFlow(ordered, anchored, headings)
    }

    /** The page's blocks in reading order, for checking the analysis by eye. */
    internal fun describe(pageIndex: Int): String {
        val page = pages.firstOrNull { it.index == pageIndex } ?: return ""
        val flow = pageFlow(page)
        return flow.main.joinToString("\n") { block -> "${block.box} ${flow.headings[block] ?: ""} ${block.text.take(60)}" }
    }

    /** Body text, as opposed to labels, captions and notes. */
    private fun isRunningText(line: PdfLine, columnWidth: Float): Boolean =
        (line.font == bodyFont && abs(line.size - bodySize) <= bodySize * 0.12f && line.text.length >= 12) ||
            (line.text.length >= 40 && line.box.width >= columnWidth * 0.5f)

    private class Candidate(var box: PdfRect, var count: Int, var hasImage: Boolean, var onlyRules: Boolean)

    private fun joinCandidates(candidates: MutableList<Candidate>, running: List<PdfLine>, beside: (PdfRect) -> Boolean) {
        fun textBetween(a: PdfRect, b: PdfRect): Boolean {
            // The stretch of page between the facing edges of the two boxes.
            val horizontal = if (a.xOverlap(b) > 0f) max(a.left, b.left) to min(a.right, b.right) else min(a.right, b.right) to max(a.left, b.left)
            val vertical = if (a.yOverlap(b) > 0f) max(a.top, b.top) to min(a.bottom, b.bottom) else min(a.bottom, b.bottom) to max(a.top, b.top)
            val gap = PdfRect(horizontal.first, vertical.first, horizontal.second, vertical.second)
            return running.any { it.box.intersects(gap) }
        }
        var joined = true
        while (joined) {
            joined = false
            loop@ for (i in candidates.indices) for (j in i + 1 until candidates.size) {
                val a = candidates[i].box
                val b = candidates[j].box
                if (beside(a) != beside(b)) continue
                val near = a.inflate(12f).intersects(b)
                val row = a.yOverlap(b) >= min(a.height, b.height) * 0.4f && a.distanceTo(b) <= 60f
                val stack = a.xOverlap(b) >= min(a.width, b.width) * 0.4f && a.distanceTo(b) <= 36f
                if (!(near || row || stack)) continue
                if (!a.intersects(b) && textBetween(a, b)) continue
                val other = candidates.removeAt(j)
                candidates[i].apply {
                    box = box.union(other.box)
                    count += other.count
                    hasImage = hasImage || other.hasImage
                    onlyRules = onlyRules && other.onlyRules
                }
                joined = true
                break@loop
            }
        }
    }

    /**
     * «FIGURE 5.4 Inputs…», «Рис. 3. Схема…», «Table 2: Results» set apart from the body text; not a
     * sentence that mentions a figure, as «Figure 5.5 shows…».
     */
    private fun isCaption(block: Block): Boolean {
        val first = block.lines.first()
        val match = captionStart.find(first.text) ?: return false
        val styled = first.font != bodyFont || block.size < bodySize * 0.95f || first.bold
        if (!styled) return false
        val label = first.text.substringBefore(' ').filter { it.isLetter() }
        val capitals = label.count { it.isUpperCase() } >= label.length * 0.7f || isOddCase(label)
        val rest = first.text.substring(match.range.last + 1).trimStart()
        return capitals || rest.isEmpty() || rest.first() in ".:—–-|" || rest.first().isUpperCase() || rest.first().isDigit()
    }

    /** «Рисунок 1 – Схема сети» in the body font: a caption only right next to its picture. */
    private fun isPlainCaption(block: Block): Boolean {
        if (block.lines.size > 3) return false
        val first = block.lines.first().text
        val match = captionStart.find(first) ?: return false
        return first.substring(match.range.last + 1).trimStart().firstOrNull()?.let { it in "–—-.:" } ?: true
    }

    private fun mergeOverlapping(boxes: MutableList<PdfRect>) {
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                if (boxes[i].inflate(2f).intersects(boxes[j])) {
                    boxes[i] = boxes[i].union(boxes[j])
                    boxes.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
    }

    private fun buildBlocks(lines: List<PdfLine>): List<Block> {
        val blocks = ArrayList<Block>()
        for (line in lines.sortedWith(compareBy({ it.box.top }, { it.box.left }))) {
            var best: Block? = null
            var bestGap = Float.MAX_VALUE
            for (block in blocks) {
                val last = block.lines.last()
                val step = line.baseline - last.baseline
                val em = max(line.size, last.size)
                if (step <= em * 0.5f || step > em * 1.75f) continue
                // Extra space between paragraphs separates blocks once the block's line pitch is known.
                if (block.lines.size >= 2) {
                    val pitch = block.lines[block.lines.size - 1].baseline - block.lines[block.lines.size - 2].baseline
                    if (step > pitch * 1.3f + 0.5f) continue
                }
                if (abs(line.size - last.size) > em * 0.2f) continue
                // Headings do not run into text and text does not run into headings, nor does a
                // short bold line of its own («Online Editors») into the paragraph under it.
                if (isHeadingLine(line) != isHeadingLine(last)) continue
                if (block.lines.size == 1 && last.bold && !line.bold && last.box.width < line.box.width * 0.6f) continue
                val overlap = line.box.xOverlap(block.box)
                val aligned = abs(line.box.left - block.box.left) <= em * 2f
                if (overlap < min(line.box.width, block.box.width) * 0.5f && !aligned) continue
                if (step < bestGap) {
                    best = block
                    bestGap = step
                }
            }
            if (best != null) best.add(line) else blocks += Block(mutableListOf(line))
        }
        return blocks
    }

    /**
     * Recursive cuts: a gutter running the full height splits columns (read left to right),
     * otherwise the topmost band comes off first.
     */
    private fun readingOrder(blocks: List<Block>): List<Block> {
        if (blocks.size <= 1) return blocks
        val region = blocks.map { it.box }.reduce(PdfRect::union)
        val byLeft = blocks.sortedBy { it.box.left }
        val columns = ArrayList<MutableList<Block>>()
        var right = -Float.MAX_VALUE
        for (block in byLeft) {
            if (columns.isEmpty() || block.box.left > right + 0.5f) {
                columns += mutableListOf(block)
                right = block.box.right
            } else {
                columns.last() += block
                right = max(right, block.box.right)
            }
        }
        if (columns.size > 1) {
            val tall = columns.count { column -> column.map { it.box }.reduce(PdfRect::union).height >= region.height * 0.3f }
            if (tall >= 2 || columns.all { it.size == 1 }) return columns.flatMap { readingOrder(it) }
        }
        val byTop = blocks.sortedBy { it.box.top }
        var bottom = byTop.first().box.bottom
        for (i in 1 until byTop.size) {
            val block = byTop[i]
            if (block.box.top >= bottom - 0.5f) {
                return readingOrder(byTop.subList(0, i)) + readingOrder(byTop.subList(i, byTop.size))
            }
            bottom = max(bottom, block.box.bottom)
        }
        if (columns.size > 1) return columns.flatMap { column -> column.sortedBy { it.box.top } }
        return topThenLeft(byTop)
    }

    /** Top to bottom; blocks that start on one line (a heading in the margin and its text) left to right. */
    private fun topThenLeft(blocks: List<Block>): List<Block> {
        val result = ArrayList<Block>(blocks.size)
        for (block in blocks) {
            var at = result.size
            while (at > 0) {
                val before = result[at - 1]
                val em = max(before.size, block.size)
                val sameLine = abs(before.box.top - block.box.top) < em * 0.8f
                if ((sameLine && before.box.left > block.box.left) || (!sameLine && before.box.top > block.box.top)) at-- else break
            }
            result.add(at, block)
        }
        return result
    }

    // ------------------------------------------------------------ paragraphs

    private class Open(val kind: PdfItemKind, val page: Int, val top: Float) {
        val text = StringBuilder()
        var last: PdfLine? = null
        var blockLeft = 0f
        var blockRight = 0f
        /** Right edge of the page's text column, for lines that are a block of their own. */
        var columnRight = 0f
        var fonts = HashMap<String, Int>()
    }

    fun chapters(): List<PdfChapter> {
        val outlineStarts = chapterStarts()
        val items = assemble(outlineStarts)
        val starts = outlineStarts.takeIf { it.size >= 2 } ?: headingStarts(items).ifEmpty { sizeStarts(items) }
        return split(items, starts).flatMap(::splitLong)
            .filter { chapter -> chapter.items.isNotEmpty() }
    }

    /** All paragraphs, headings, notes and pictures of the book in reading order. */
    private fun assemble(boundaries: List<Start>): List<PdfItem> {
        val items = ArrayList<PdfItem>()
        var boundaryIndex = 0
        var open: Open? = null
        val pending = ArrayList<Floating>()

        fun emitFloat(floating: Floating) {
            val pageIndex = floating.page
            when (floating.kind) {
                FloatKind.FIGURE -> {
                    val region = floating.region ?: return
                    items += PdfItem(PdfItemKind.FIGURE, "", pageIndex, region.top, region)
                    floating.caption?.let { caption ->
                        items += PdfItem(PdfItemKind.CAPTION, tidy(repairCase(caption.lines, joined(caption))), pageIndex, caption.box.top)
                    }
                }
                FloatKind.CAPTION -> floating.blocks.forEach { block ->
                    items += PdfItem(PdfItemKind.CAPTION, tidy(repairCase(block.lines, joined(block))), pageIndex, block.box.top)
                }
                FloatKind.NOTE -> floating.blocks.forEach { block ->
                    val heading = headingKind(block)
                    when {
                        heading != null -> items += PdfItem(PdfItemKind.SUBHEADING, tidy(titleCase(block)), pageIndex, block.box.top)
                        isCaption(block) -> items += PdfItem(PdfItemKind.CAPTION, tidy(repairCase(block.lines, joined(block))), pageIndex, block.box.top)
                        else -> paragraphsOf(block).forEach { text -> items += PdfItem(PdfItemKind.NOTE, text, pageIndex, block.box.top) }
                    }
                }
            }
        }

        fun flush() {
            val current = open ?: return
            open = null
            val text = tidy(current.text.toString())
            if (text.isEmpty()) return
            val broken = current.fonts.filterKeys { it in brokenCaseFonts }.values.sum()
            val fixed = if (broken * 2 > current.fonts.values.sum()) sentenceCase(text, spellings) else text
            items += PdfItem(current.kind, fixed, current.page, current.top)
        }

        fun flushWithFloats() {
            flush()
            pending.forEach(::emitFloat)
            pending.clear()
        }

        for (page in pages) {
            // A paragraph never runs from one chapter into the next.
            fun crossBoundaries(top: Float) {
                while (boundaryIndex < boundaries.size) {
                    val next = boundaries[boundaryIndex]
                    if (page.index < next.page || (page.index == next.page && top < next.top - 2f)) break
                    flushWithFloats()
                    boundaryIndex++
                }
            }
            crossBoundaries(0f)
            val recognized = page.ocrParagraphs
            if (recognized != null) {
                flushWithFloats()
                recognized.forEach { items += PdfItem(PdfItemKind.BODY, it, page.index, 0f) }
                continue
            }
            val flow = pageFlow(page)
            pending += flow.anchored[-1].orEmpty()
            for ((blockIndex, block) in flow.main.withIndex()) {
                crossBoundaries(block.box.top)
                val heading = flow.headings[block]
                if (heading != null) {
                    flushWithFloats()
                    items += PdfItem(heading, tidy(titleCase(block)), page.index, block.box.top)
                } else {
                    for ((lineIndex, line) in block.lines.withIndex()) {
                        val current = open
                        val continues = current != null && continues(current, line, block, lineIndex)
                        if (!continues) {
                            flushWithFloats()
                            open = Open(PdfItemKind.BODY, page.index, line.box.top)
                        }
                        val paragraph = open!!
                        append(paragraph, line)
                        paragraph.blockLeft = block.box.left
                        paragraph.blockRight = block.box.right
                        paragraph.columnRight = columnOf(page).endInclusive
                    }
                }
                pending += flow.anchored[blockIndex].orEmpty()
            }
        }
        flushWithFloats()
        return items
    }

    /** Cuts the book at the chapter starts; what comes before the first one is the front matter. */
    private fun split(items: List<PdfItem>, starts: List<Start>): List<PdfChapter> {
        val chapters = ArrayList<PdfChapter>()
        var title = bookTitle
        var current = ArrayList<PdfItem>()
        var next = 0
        for (item in items) {
            while (next < starts.size) {
                val start = starts[next]
                if (item.page < start.page || (item.page == start.page && item.top < start.top - 2f)) break
                if (current.isNotEmpty()) chapters += PdfChapter(title, dropRepeatedTitle(title, current))
                current = ArrayList()
                title = start.title
                next++
            }
            current += item
        }
        if (current.isNotEmpty()) chapters += PdfChapter(title, dropRepeatedTitle(title, current))
        return chapters
    }

    /**
     * Without bookmarks: lines such as «Глава 3» or «Chapter 3: …». The contents page lists them
     * too, so of two lines with the same number the one followed by more text wins.
     */
    private fun headingStarts(items: List<PdfItem>): List<Start> {
        val candidates = items.withIndex().filter { (_, item) ->
            item.kind in setOf(PdfItemKind.HEADING, PdfItemKind.SUBHEADING, PdfItemKind.BODY) &&
                item.text.length <= 120 && chapterLine.matches(item.text)
        }
        if (candidates.size >= 2) {
            val weight = candidates.mapIndexed { n, (index, _) ->
                val end = candidates.getOrNull(n + 1)?.index ?: items.size
                (index + 1 until end).sumOf { items[it].text.length }
            }
            val best = LinkedHashMap<String, Int>()
            candidates.forEachIndexed { n, (_, item) ->
                val match = chapterLine.find(item.text)!!
                val key = (match.groupValues[1].ifEmpty { match.groupValues[2] }).lowercase()
                val kept = best[key]
                if (kept == null || weight[n] > weight[kept]) best[key] = n
            }
            val chosen = best.values.sorted().map { candidates[it].value }
            if (chosen.size >= 2) return chosen.map { Start(it.text, it.page, it.top) }
        }
        // Otherwise the largest headings, when there are a few of them.
        val headings = items.filter { it.kind == PdfItemKind.HEADING }
        if (headings.size in 3..300) return headings.map { Start(it.text, it.page, it.top) }
        return emptyList()
    }

    /** A book without any structure: parts of about 30 000 letters, cut at a heading when there is one. */
    private fun sizeStarts(items: List<PdfItem>): List<Start> {
        if (items.isEmpty()) return emptyList()
        val cuts = ArrayList<Int>()
        var size = 0
        for ((index, item) in items.withIndex()) {
            val heading = item.kind == PdfItemKind.HEADING || item.kind == PdfItemKind.SUBHEADING
            if (index > 0 && (size >= 30_000 || (heading && size >= 12_000))) {
                cuts += index
                size = 0
            }
            size += item.text.length
        }
        if (cuts.isEmpty()) return emptyList()
        val bounds = listOf(0) + cuts + items.size
        return (0 until bounds.size - 1).map { part ->
            val first = items[bounds[part]]
            val last = items[bounds[part + 1] - 1]
            val heading = first.kind == PdfItemKind.HEADING || first.kind == PdfItemKind.SUBHEADING
            Start(if (heading) first.text else pageRange(first.page, last.page), first.page, first.top)
        }
    }

    private fun pageRange(from: Int, to: Int) = if (from == to) "Страница ${from + 1}" else "Страницы ${from + 1}–${to + 1}"

    /** Very long chapters are hard to page through and to narrate: cut them at headings. */
    private fun splitLong(chapter: PdfChapter): List<PdfChapter> {
        val total = chapter.items.sumOf { it.text.length }
        if (total <= 120_000) return listOf(chapter)
        val parts = ArrayList<PdfChapter>()
        var current = ArrayList<PdfItem>()
        var size = 0
        var title = chapter.title
        for (item in chapter.items) {
            val heading = item.kind == PdfItemKind.HEADING || item.kind == PdfItemKind.SUBHEADING
            if (current.isNotEmpty() && (size >= 80_000 || (heading && size >= 40_000))) {
                parts += PdfChapter(title, current)
                current = ArrayList()
                size = 0
                title = if (heading) "${chapter.title}. ${item.text}" else "${chapter.title} (${parts.size + 1})"
            }
            current += item
            size += item.text.length
        }
        if (current.isNotEmpty()) parts += PdfChapter(title, current)
        return parts
    }

    /** Whether [line], the [lineIndex]-th line of [block], goes on with the open paragraph. */
    private fun continues(open: Open, line: PdfLine, block: Block, lineIndex: Int): Boolean {
        val last = open.last ?: return false
        if (open.kind != PdfItemKind.BODY) return false
        val text = line.text
        // A list item or a line of dialogue.
        if (text.first() in bulletGlyphs || text.startsWith("– ") || text.startsWith("— ")) return false
        val previousText = last.text
        if (tocEntry.matches(previousText)) return false
        val em = max(line.size, last.size)
        if (abs(line.size - last.size) > em * 0.2f) return false
        val lastEnds = previousText.trimEnd().lastOrNull()?.let { it in sentenceEnds } ?: false
        // A line that is a block of its own is measured against the whole column.
        val edge = if (lineIndex == 0) max(open.blockRight, open.columnRight) else open.blockRight
        val lastShort = last.box.right < edge - em * 2.5f
        val startsLower = text.first().isLowerCase() || text.first() in ",;)]»”"
        if (lineIndex > 0) {
            // Inside one block: a new paragraph shows as an indent, a short line or a gap.
            val indent = line.box.left - block.box.left
            if (indent > em * 0.8f && block.lines.count { abs(it.box.left - block.box.left) <= 1f } >= 2 && !startsLower) return false
            val step = line.baseline - last.baseline
            val usualStep = typicalStep(block)
            if (usualStep > 0f && step > usualStep * 1.3f && !startsLower) return false
            if (lastShort && lastEnds && !startsLower) return false
            if (numberedItem.containsMatchIn(text) && (lastEnds || lastShort)) return false
            // An index: entries end with page numbers, and the next entry starts with a word.
            if (previousText.last().isDigit() && text.first().isLetter() &&
                block.lines.count { it.text.last().isDigit() } * 5 >= block.lines.size * 2
            ) return false
            // Lines far shorter than the column, one after another: an address, credits, verse.
            val halfLine = last.box.right < open.blockLeft + (open.blockRight - open.blockLeft) * 0.5f
            if (halfLine && !startsLower && previousText.last() !in ",-­‐") return false
            return true
        }
        // A new block, maybe on the next page or in the next column: the paragraph goes on when the
        // previous line reached the edge and did not end a sentence, or when this one starts in lower case.
        if (startsLower && !(lastEnds && lastShort)) return true
        if (lastEnds && !lastShort && previousText.endsWith(".") && text.first().isUpperCase()) {
            // A full line ending with a full stop, then a capital: most often a new paragraph, unless
            // the block starts exactly at the page top, where the page break may cut a paragraph.
            return false
        }
        return !lastEnds && !lastShort
    }

    private fun typicalStep(block: Block): Float {
        if (block.lines.size < 2) return 0f
        val steps = (1 until block.lines.size).map { block.lines[it].baseline - block.lines[it - 1].baseline }.sorted()
        return steps[steps.size / 2]
    }

    private fun append(open: Open, line: PdfLine) {
        val text = line.text
        val builder = open.text
        if (builder.isNotEmpty()) {
            val lastChar = builder.last()
            val lastToken = builder.substring(builder.lastIndexOf(' ') + 1)
            if ((lastChar == '-' || lastChar == '­' || lastChar == '‐') && builder.length >= 2 && builder[builder.length - 2].isLetter() &&
                text.first().isLowerCase()
            ) {
                val head = lastToken.dropLast(1).trimStart('(', '“', '"', '«', '‘').substringAfterLast('-')
                val tail = text.takeWhile { it.isLetter() }
                val joined = (head + tail).lowercase()
                val hyphenated = "$head-$tail".lowercase()
                builder.setLength(builder.length - 1)
                // Keep the hyphen of a compound word the book spells with one, or of two words it
                // knows apart («one-third»); join a word that hyphenation cut («chang-ing»).
                val compound = when {
                    lastChar == '­' -> false
                    hyphenated in knownWords && joined !in knownWords -> true
                    joined in knownWords -> false
                    else -> head.length >= 2 && head.lowercase() in knownWords && tail.lowercase() in knownWords
                }
                if (compound) builder.append('-')
            } else if ((lastToken.contains("www.", ignoreCase = true) || lastToken.contains("://")) &&
                lastChar in "/._-=?&" && (text.first().isLowerCase() || text.first().isDigit() || lastChar == '/')
            ) {
                // A web address broken at the end of a line goes on without a space.
            } else if (lastChar != ' ') {
                builder.append(' ')
            }
        }
        builder.append(text)
        open.last = line
        open.fonts.merge(line.font, line.weight, Int::plus)
    }

    /** The block's lines as one piece of text, with words cut by hyphenation joined again. */
    private fun joined(block: Block): String {
        val open = Open(PdfItemKind.BODY, block.page, block.box.top)
        block.lines.forEach { append(open, it) }
        return open.text.toString()
    }

    /** Lines of a note or a box as paragraphs of their own. */
    private fun paragraphsOf(block: Block): List<String> {
        val result = ArrayList<String>()
        var open: Open? = null
        for ((index, line) in block.lines.withIndex()) {
            val current = open
            if (current == null || !continues(current, line, block, index)) {
                current?.let { result += tidy(repairCase(block.lines, it.text.toString())) }
                open = Open(PdfItemKind.BODY, line.page, line.box.top).also {
                    it.blockLeft = block.box.left
                    it.blockRight = block.box.right
                }
            }
            append(open!!, line)
        }
        open?.let { result += tidy(repairCase(block.lines, it.text.toString())) }
        return result.filter { it.isNotEmpty() }
    }

    private fun repairCase(lines: List<PdfLine>, text: String): String {
        val broken = lines.sumOf { if (it.font in brokenCaseFonts) it.weight else 0 }
        return if (broken * 2 > lines.sumOf { it.weight }) sentenceCase(text, spellings) else text
    }

    /**
     * Headings set in a font that lost its capitals («cOncePT sTaGe») read best in title case:
     * «Concept Stage». Names and abbreviations keep the spelling the book uses elsewhere.
     */
    private fun titleCase(block: Block): String {
        val text = block.text
        val broken = block.lines.any { it.font in brokenCaseFonts } || text.split(' ').any { isOddCase(it.filter(Char::isLetter)) }
        if (!broken) return text
        val minor = setOf("a", "an", "and", "as", "at", "but", "by", "for", "in", "of", "on", "or", "the", "to", "vs", "vs.", "with", "from", "into", "per")
        val words = text.split(' ')
        return words.mapIndexed { index, word ->
            val lower = word.lowercase()
            val core = lower.trim { !it.isLetterOrDigit() }
            val known = spellings[core]
            val letter = lower.indexOfFirst { it.isLetter() }
            when {
                Regex("^[ivx]{1,4}$").matches(core) && index > 0 -> word.uppercase()
                known != null && known != known.lowercase().replaceFirstChar { it.uppercaseChar() } -> lower.replaceFirst(core, known)
                index > 0 && index < words.lastIndex && core in minor -> lower
                letter < 0 -> lower
                else -> lower.substring(0, letter) + lower[letter].uppercaseChar() + lower.substring(letter + 1)
            }
        }.joinToString(" ")
    }

    // ------------------------------------------------------------ chapters

    private class Start(val title: String, val page: Int, val top: Float)

    /** Chapter starts from the bookmarks. */
    private fun chapterStarts(): List<Start> {
        val lastPage = pages.lastOrNull()?.index ?: 0
        val usable = outline.filter { it.page in 0..lastPage && it.title.isNotBlank() }
        if (usable.size < 2) return emptyList()
        val result = ArrayList<Start>()
        // Bookmarks come in reading order, each followed by its own subentries. A long part or a
        // single entry for the whole book is split into the chapters under it.
        fun take(from: Int, to: Int, depth: Int) {
            if (from > to) return
            val level = (from..to).minOf { usable[it].level }
            var i = from
            while (i <= to) {
                val entry = usable[i]
                if (entry.level > level) {
                    i++
                    continue
                }
                var end = i + 1
                while (end <= to && usable[end].level > entry.level) end++
                result += Start(tidy(entry.title), entry.page, entry.top)
                val nextPage = usable.getOrNull(end)?.page ?: (lastPage + 1)
                if (nextPage - entry.page > 50 && end > i + 1 && depth < 2) take(i + 1, end - 1, depth + 1)
                i = end
            }
        }
        take(0, usable.lastIndex, 0)
        return result.sortedWith(compareBy({ it.page }, { it.top }))
    }

    private fun dropRepeatedTitle(title: String, items: List<PdfItem>): List<PdfItem> {
        val key = letters(title)
        if (key.isEmpty()) return items
        var drop = 0
        while (drop < items.size && drop < 4) {
            val item = items[drop]
            val itemKey = letters(item.text)
            val repeats = itemKey.isNotEmpty() && key.contains(itemKey) &&
                (item.kind == PdfItemKind.HEADING || item.kind == PdfItemKind.SUBHEADING || item.text.length <= 60)
            if (!repeats) break
            drop++
        }
        return items.drop(drop)
    }

    private fun letters(value: String) = value.lowercase().filter { it.isLetterOrDigit() }
}

/** «InfoDispBold-Roman» and «InfoDispRegular-Roman» are one family: «InfoDisp». */
internal fun fontFamily(font: String): String = font.substringBefore('-').substringBefore(',')
    .replace(Regex("(?i)(?:regular|roman|bold|semibold|demibold|demi|medium|light|black|heavy|book|italic|oblique|condensed|narrow|mt|ps)+$"), "")
    .ifEmpty { font }

/** A word whose capitals sit where no spelling puts them: «desiGn», «cOncePT», «FIGURe». */
internal fun isOddCase(word: String): Boolean {
    if (word.length < 3 || word.all { it.isLowerCase() } || word.all { it.isUpperCase() }) return false
    if (word[0].isUpperCase() && word.drop(1).all { it.isLowerCase() }) return false
    // CamelCase names: «JavaScript», «PowerPoint», «iPhone», «McDonald».
    if (Regex("^(?:\\p{Lu}?\\p{Ll}+)(?:\\p{Lu}\\p{Ll}+)+$").matches(word)) return false
    if (Regex("^\\p{Lu}+s$").matches(word)) return false
    return true
}

/** Collapses spaces, turns bullet glyphs into «•» and dot leaders of a contents page into «…». */
internal fun tidy(value: String): String {
    var text = value.replace(Regex("\\s+"), " ").trim()
    if (text.isEmpty()) return text
    if (text[0] in bulletGlyphs) text = "• " + text.drop(1).trimStart()
    text = text.replace(dotLeader, " … ")
    return text.replace(Regex("\\s+"), " ").trim()
}

/**
 * Restores capitals that a broken font lost: at the start of the text and of every sentence, the
 * English «I», and whole words written in capitals («desiGn» → «DESIGN»).
 */
internal fun sentenceCase(text: String, spellings: Map<String, String> = emptyMap()): String {
    val result = StringBuilder(text.length)
    var sentenceStart = true
    val words = text.split(' ')
    for ((index, word) in words.withIndex()) {
        if (index > 0) result.append(' ')
        var value = word
        val letters = value.filter { it.isLetter() }
        val core = value.trim { !it.isLetterOrDigit() }
        val known = spellings[core.lowercase()]
        when {
            // «simWar» → «SimWar», «dvd» → «DVD»: the book's own spelling wins.
            known != null && core.isNotEmpty() -> value = value.replaceFirst(core, known)
            // In a font that lost its capitals a capital inside a word means the word was in capitals.
            isOddCase(letters) || Regex("\\p{Ll}\\p{Lu}").containsMatchIn(letters) -> value = value.uppercase()
        }
        if (value == "i" || value.startsWith("i’") || value.startsWith("i'")) value = "I" + value.drop(1)
        if (sentenceStart) {
            val first = value.indexOfFirst { it.isLetter() }
            if (first >= 0 && value[first].isLowerCase()) {
                value = value.substring(0, first) + value[first].uppercaseChar() + value.substring(first + 1)
            }
        }
        result.append(value)
        val end = value.trimEnd('"', '”', '»', ')', '’')
        // «NOTE remember…», «TIP if you…»: a label in capitals opens a sentence.
        val label = index == 0 && ((end.length in 2..12 && end.all { it.isUpperCase() }) || captionStart.containsMatchIn(text))
        // A number after a label («FIGURE 5.4 inputs…») does not start the sentence yet.
        if (label || end.none { it.isLetter() } && sentenceStart) {
            sentenceStart = true
            continue
        }
        sentenceStart = end.isNotEmpty() && end.last() in ".!?" && !isAbbreviation(end)
    }
    return result.toString()
}

private fun isAbbreviation(word: String): Boolean {
    val lower = word.lowercase().trimStart('(', '“', '"', '«')
    return lower in setOf("e.g.", "i.e.", "etc.", "vs.", "cf.", "mr.", "mrs.", "dr.", "st.", "no.", "fig.", "p.", "pp.", "т.е.", "т.д.", "т.п.", "др.", "см.", "рис.") ||
        Regex("^\\p{L}\\.$").matches(lower)
}
