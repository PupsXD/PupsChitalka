package com.ozvuchka.app.ui

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.rememberTextMeasurer
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ReaderPaginationTest {
    @get:Rule
    val compose = createComposeRule()

    private val text = "Абзац с достаточным количеством слов, чтобы занять несколько строк на странице читалки."

    private fun paginate(paragraphs: List<String>, styles: Map<Int, ParagraphStyle>, width: Int = 900, height: Int = 1_500): List<ReaderPage> {
        lateinit var pages: List<ReaderPage>
        compose.setContent {
            val measurer = rememberTextMeasurer()
            val density = LocalDensity.current
            pages = paginateChapter(paragraphs, "Глава", "ГЛАВА 1  /  1", ReaderTypography(), "ru", width, height, density, measurer, styles)
        }
        compose.waitForIdle()
        return pages
    }

    @Test
    fun aPictureTallerThanThePageIsScaledToFitIt() {
        val pages = paginate(
            listOf(text, "", text),
            mapOf(1 to ParagraphStyle(ParagraphKind.IMAGE, "tall.webp", 800, 4_000)),
        )
        val picture = pages.flatMap { it.blocks }.single { it.style?.kind == ParagraphKind.IMAGE }
        assertTrue(picture.heightPx <= 1_500)
        // The shape is kept: a fifth as wide as tall.
        assertEquals(picture.heightPx / 5f, picture.widthPx.toFloat(), 2f)
        // Every paragraph of the chapter is on some page, in order.
        assertEquals(listOf(0, 1, 2), pages.flatMap { it.blocks }.map { it.paragraphIndex }.distinct())
    }

    @Test
    fun aHeadingNeverEndsAPage() {
        val paragraphs = List(160) { index -> if (index % 7 == 3) "Подзаголовок $index" else text }
        val styles = paragraphs.indices.filter { it % 7 == 3 }.associateWith { ParagraphStyle(ParagraphKind.SUBHEADING) }
        val pages = paginate(paragraphs, styles)
        assertTrue(pages.size > 2)
        pages.dropLast(1).forEach { page ->
            assertFalse(page.blocks.last().style?.kind == ParagraphKind.SUBHEADING)
        }
    }

    @Test
    fun tableRowsGetTheirHeightAndStayWhole() {
        val rows = List(30) { "Строка $it\t${it * 10}\tзначение" }
        val styles = rows.indices.associateWith { ParagraphStyle(if (it == 0) ParagraphKind.TABLE_HEADER else ParagraphKind.TABLE_ROW) }
        val pages = paginate(rows, styles)
        val blocks = pages.flatMap { it.blocks }
        assertEquals(30, blocks.size)
        assertTrue(blocks.all { it.heightPx > 0 && it.visibleLength == it.text.length })
    }
}
