package com.ozvuchka.app.importer.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private val width = 531f
    private val height = 657f

    /** Glyphs of [text] set from [x] on [baseline], each letter half an em wide, spaces included. */
    private fun text(text: String, x: Float, baseline: Float, size: Float = 9f, font: String = "Serif"): List<PdfGlyph> {
        var left = x
        return text.map { c ->
            val advance = if (c == ' ') size * 0.28f else size * 0.5f
            PdfGlyph(c.toString(), left, baseline, advance, size, font).also { left += advance }
        }
    }

    private fun page(index: Int, glyphs: List<PdfGlyph>, shapes: List<PdfShape> = emptyList()) =
        analyzePage(PdfPageInput(index, width, height, glyphs, shapes))

    private val body = listOf(
        "Discrete mechanics offer more opportunities for innovation than",
        "many of the current forms of continuous mechanics do. As games",
        "and genres change, the definitions of physical mechanics evolve.",
    )

    private fun bodyLines(from: Float, lines: List<String> = body, x: Float = 126f) =
        lines.flatMapIndexed { i, line -> text(line, x, from + i * 12f) }

    @Test
    fun aNoteInTheMarginDoesNotInterleaveWithTheColumnBesideIt() {
        // The note is drawn after the main text, on the same baselines, left of the column.
        val glyphs = bodyLines(100f) + bodyLines(150f, listOf("Looking back at four decades of game history, physics have evolved.")) +
            text("NOTE The mecha-", 20f, 100f, 8f, "Sans") + text("nistic view is a", 20f, 111f, 8f, "Sans") +
            text("narrow one.", 20f, 122f, 8f, "Sans")
        val chapters = PdfBookLayout(listOf(page(0, glyphs)), emptyList(), "Book").chapters()
        val items = chapters.single().items
        assertEquals(
            listOf(PdfItemKind.BODY, PdfItemKind.NOTE, PdfItemKind.BODY),
            items.map { it.kind },
        )
        assertEquals(body.joinToString(" "), items[0].text)
        assertEquals("NOTE The mechanistic view is a narrow one.", items[1].text)
    }

    @Test
    fun runningHeadersPageNumbersAndWatermarksAreDropped() {
        val pages = (0 until 6).map { index ->
            val header = if (index % 2 == 0) text("${index + 10}  GAME MECHANICS", 40f, 45f, 8f) else text("DESIGNING GAMES  ${index + 10}", 300f, 45f, 8f)
            // A watermark beside the page, off its right edge.
            val watermark = text("ptg8274339", 631f, 326f)
            page(index, header + watermark + bodyLines(100f) + text("${index + 1}", 260f, 620f, 8f))
        }
        val text = PdfBookLayout(pages, emptyList(), "Book").chapters().flatMap { it.items }.joinToString(" ") { it.text }
        assertFalse(text, text.contains("GAME MECHANICS"))
        assertFalse(text, text.contains("DESIGNING"))
        assertFalse(text, text.contains("ptg"))
        assertFalse(text, Regex("\\b1[0-3]\\b").containsMatchIn(text))
    }

    @Test
    fun hyphenatedWordsJoinButCompoundWordsKeepTheirHyphen() {
        val glyphs = bodyLines(
            100f,
            listOf(
                "A first-person shooter can take anywhere between one-",
                "third and half of the time to tune the game mechan-",
                "ics that players enjoy in the first-person view. One in",
                "three players quits, and a third of them come back.",
            ),
        )
        val paragraph = PdfBookLayout(listOf(page(0, glyphs)), emptyList(), "Book").chapters().single().items.single().text
        assertTrue(paragraph, paragraph.contains("one-third"))
        assertTrue(paragraph, paragraph.contains("game mechanics that"))
    }

    @Test
    fun aDiagramBecomesOnePictureWithItsCaptionAndWithoutItsLabels() {
        val glyphs = bodyLines(100f) +
            // Labels drawn in the diagram, and its caption in the margin beside it.
            text("Pool", 150f, 250f, 7f, "Sans") + text("Drain", 300f, 250f, 7f, "Sans") +
            text("FIGURE 5.2", 440f, 215f, 8f, "SansBold") + text("Pools", 440f, 226f, 8f, "Sans") +
            bodyLines(300f, listOf("The text goes on under the picture and ends here."))
        val shapes = listOf(
            PdfShape(PdfRect(140f, 200f, 180f, 240f), ShapeKind.PATH),
            PdfShape(PdfRect(290f, 200f, 330f, 240f), ShapeKind.PATH),
            // An arrow between the two nodes.
            PdfShape(PdfRect(180f, 219f, 290f, 221f), ShapeKind.PATH),
        )
        val items = PdfBookLayout(listOf(page(0, glyphs, shapes)), emptyList(), "Book").chapters().single().items
        assertEquals(listOf(PdfItemKind.BODY, PdfItemKind.FIGURE, PdfItemKind.CAPTION, PdfItemKind.BODY), items.map { it.kind })
        val region = items[1].region!!
        assertTrue(region.left <= 140f && region.right >= 330f && region.bottom >= 250f)
        assertEquals("FIGURE 5.2 Pools", items[2].text)
        assertFalse(items.any { it.text.contains("Drain") })
    }

    @Test
    fun bookmarksMakeChaptersAndTheOpeningPageRepeatsNoTitle() {
        val pages = listOf(
            page(0, text("Contents", 126f, 90f, 18f) + bodyLines(120f)),
            page(1, text("CHAPTER 1", 126f, 90f, 24f) + text("Designing Game Mechanics", 126f, 130f, 24f) + bodyLines(170f)),
            page(2, bodyLines(100f)),
            page(3, text("CHAPTER 2", 126f, 90f, 24f) + bodyLines(170f)),
        )
        val outline = listOf(
            PdfOutlineEntry("Contents", 1, 0),
            PdfOutlineEntry("CHAPTER 1 Designing Game Mechanics", 1, 1),
            PdfOutlineEntry("CHAPTER 2 Emergence", 1, 3),
        )
        val chapters = PdfBookLayout(pages, outline, "Book").chapters()
        assertEquals(listOf("Contents", "CHAPTER 1 Designing Game Mechanics", "CHAPTER 2 Emergence"), chapters.map { it.title })
        assertEquals(PdfItemKind.BODY, chapters[1].items.first().kind)
        assertTrue(chapters[1].items.first().text.startsWith("Discrete mechanics"))
    }

    @Test
    fun withoutBookmarksChapterHeadingsWinOverTheirContentsEntries() {
        val pages = listOf(
            page(0, text("Chapter 1: Basics", 126f, 100f) + text("Chapter 2: Physics", 126f, 112f)),
            page(1, text("Chapter 1: Basics", 126f, 100f, 16f) + bodyLines(130f)),
            page(2, text("Chapter 2: Physics", 126f, 100f, 16f) + bodyLines(130f)),
        )
        val chapters = PdfBookLayout(pages, emptyList(), "Book").chapters()
        assertEquals(listOf("Book", "Chapter 1: Basics", "Chapter 2: Physics"), chapters.map { it.title })
        assertTrue(chapters[1].items.first().text.startsWith("Discrete"))
    }

    @Test
    fun capitalsLostByABrokenFontComeBack() {
        assertEquals("However, we do not. It is what it is.", sentenceCase("however, we do not. it is what it is."))
        assertEquals("TIP Inkscape runs on Mac OS X.", sentenceCase("tIp inkscape runs on Mac oS X."))
        assertEquals("FIGURE 5.4 Inputs and outputs", sentenceCase("FIGURe 5.4 inputs and outputs"))
        assertEquals("Play the DVD in SimWar.", sentenceCase("play the dvd in simWar.", mapOf("dvd" to "DVD", "simwar" to "SimWar")))
    }

    @Test
    fun aListBulletStaysWithItsItem() {
        val glyphs = listOf(PdfGlyph("•", 110f, 100f, 3f, 9f, "Sans")) + text("Physics. Game mechanics define physics.", 126f, 100f) +
            listOf(PdfGlyph("•", 110f, 112f, 3f, 9f, "Sans")) + text("Economy. Resources are collected.", 126f, 112f)
        val items = PdfBookLayout(listOf(page(0, glyphs)), emptyList(), "Book").chapters().single().items
        assertEquals(listOf("• Physics. Game mechanics define physics.", "• Economy. Resources are collected."), items.map { it.text })
    }

    @Test
    fun onlyAScannedBookIsSentToOcr() {
        val scan = PdfShape(PdfRect(0f, 0f, width, height), ShapeKind.IMAGE)
        val digital = List(10) { page(it, bodyLines(100f)) } + page(10, emptyList(), listOf(scan))
        assertTrue(pagesToRecognize(digital).isEmpty())
        val scanned = List(6) { page(it, emptyList(), listOf(scan)) }
        assertEquals(6, pagesToRecognize(scanned).size)
    }
}
