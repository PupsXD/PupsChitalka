package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlBlocksTest {
    @Test
    fun headingsPicturesCaptionsAndTablesKeepTheirLook() {
        val body = Jsoup.parse(
            """
            <h2>Pools</h2>
            <p>A pool gathers resources.</p>
            <figure><img src="pool.png" alt="A pool"/><figcaption>Figure 1. A pool</figcaption></figure>
            <table><tr><th>Tweak</th><th>Wins</th></tr><tr><td>No tweaks</td><td>929</td></tr></table>
            <div class="note">Tip: pools can be empty.</div>
            <img src="missing.png" alt="Lost picture"/>
            """.trimIndent(),
        ).body()
        val picture = ParagraphStyle(ParagraphKind.IMAGE, "img1.png", 640, 480)
        val blocks = htmlBlocks(body) { element -> if (element.attr("src") == "pool.png") picture else null }
        assertEquals(
            listOf(
                "Pools" to ParagraphStyle(ParagraphKind.HEADING),
                "A pool gathers resources." to null,
                "" to picture,
                "Figure 1. A pool" to ParagraphStyle(ParagraphKind.CAPTION),
                "Tweak\tWins" to ParagraphStyle(ParagraphKind.TABLE_HEADER),
                "No tweaks\t929" to ParagraphStyle(ParagraphKind.TABLE_ROW),
                "Tip: pools can be empty." to ParagraphStyle(ParagraphKind.NOTE),
                "Иллюстрация: Lost picture" to null,
            ),
            blocks,
        )
        val chapter = chapterOf("Chapter", blocks)
        assertEquals(setOf(0, 2, 3, 4, 5, 6), chapter.styles.keys)
    }

    @Test
    fun aLongChapterIsSplitWithItsLooks() {
        val paragraphs = List(6) { "x".repeat(10) }
        val chapter = Chapter("Book", paragraphs, styles = mapOf(1 to ParagraphStyle(ParagraphKind.HEADING), 4 to ParagraphStyle(ParagraphKind.CAPTION)))
        val parts = splitLongChapter(chapter, maxChapterChars = 30)
        assertEquals(listOf("Book", "Часть 2"), parts.map { it.title })
        assertEquals(mapOf(1 to ParagraphStyle(ParagraphKind.HEADING)), parts[0].styles)
        assertEquals(mapOf(1 to ParagraphStyle(ParagraphKind.CAPTION)), parts[1].styles)
    }
}
