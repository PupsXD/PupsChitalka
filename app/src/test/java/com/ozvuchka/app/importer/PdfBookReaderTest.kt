package com.ozvuchka.app.importer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.ozvuchka.app.data.ParagraphKind
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/** The PDF reader on pdfbox-android, end to end: a real file in, chapters of paragraphs out. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PdfBookReaderTest {
    private val height = 657f

    private fun PDPageContentStream.line(text: String, x: Float, top: Float, size: Float = 9f, font: PDFont = PDType1Font.TIMES_ROMAN) {
        beginText()
        setFont(font, size)
        newLineAtOffset(x, height - top)
        showText(text)
        endText()
    }

    private fun PDPageContentStream.header(number: Int) {
        line("GAME MECHANICS", 126f, 40f, 8f, PDType1Font.HELVETICA)
        line("$number", 260f, 630f, 8f, PDType1Font.HELVETICA)
        // A watermark beside the page.
        line("ptg8274339", 600f, 330f)
    }

    private val paragraph = listOf(
        "Discrete mechanics offer more opportunities for innovation than",
        "many of the current forms of continuous mechanics do. As games",
        "and genres change, the definitions of physical mechanics evolve.",
    )

    @Test
    fun aBookIsReadInReadingOrderWithNotesFiguresAndChapters() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PDFBoxResourceLoader.init(context)
        val file = File.createTempFile("book-", ".pdf")
        val pictures = Files.createTempDirectory("pdf-pictures").toFile()
        try {
            PDDocument().use { document ->
                val pages = (1..4).map { number ->
                    PDPage(PDRectangle(531f, height)).also { page ->
                        document.addPage(page)
                        PDPageContentStream(document, page).use { stream ->
                            stream.header(number)
                            when (number) {
                                1 -> {
                                    stream.line("Mechanics and Design", 126f, 90f, 18f, PDType1Font.HELVETICA_BOLD)
                                    paragraph.forEachIndexed { i, text -> stream.line(text, 126f, 120f + i * 12f) }
                                    // A note in the margin, on the same lines as the paragraph.
                                    listOf("NOTE The mecha-", "nistic view is a", "narrow one.").forEachIndexed { i, text ->
                                        stream.line(text, 20f, 120f + i * 11f, 7f, PDType1Font.HELVETICA)
                                    }
                                    // A diagram: two boxes and an arrow, and its caption.
                                    stream.addRect(140f, height - 300f, 60f, 50f)
                                    stream.addRect(300f, height - 300f, 60f, 50f)
                                    stream.addRect(200f, height - 276f, 100f, 2f)
                                    stream.fill()
                                    stream.line("FIGURE 1.1 Two nodes", 140f, 320f, 8f, PDType1Font.HELVETICA_BOLD)
                                    stream.line("The text goes on under the picture and ends here.", 126f, 360f)
                                }
                                else -> paragraph.forEachIndexed { i, text -> stream.line(text, 126f, 100f + i * 12f) }
                            }
                        }
                    }
                }
                val outline = PDDocumentOutline()
                listOf("Chapter 1" to pages[0], "Chapter 2" to pages[2]).forEach { (title, page) ->
                    outline.addLast(PDOutlineItem().apply {
                        this.title = title
                        destination = PDPageFitDestination().apply { this.page = page }
                    })
                }
                document.documentCatalog.documentOutline = outline
                document.save(file)
            }

            val book = readPdf(context, file, "Fallback", FolderImageSink(pictures)) {}

            assertEquals(listOf("Chapter 1", "Chapter 2"), book.chapters.map { it.title })
            val first = book.chapters[0]
            val kinds = first.paragraphs.indices.map { first.styles[it]?.kind }
            assertEquals(ParagraphKind.HEADING, kinds[0])
            assertEquals("Mechanics and Design", first.paragraphs[0])
            assertEquals(paragraph.joinToString(" "), first.paragraphs[1])
            assertEquals(ParagraphKind.NOTE, kinds[2])
            assertEquals("NOTE The mechanistic view is a narrow one.", first.paragraphs[2])
            // The caption follows its picture; the picture itself needs the system renderer.
            val caption = first.paragraphs.indexOf("FIGURE 1.1 Two nodes")
            assertEquals(ParagraphKind.CAPTION, kinds[caption])
            assertTrue(first.paragraphs[caption + 1].startsWith("The text goes on"))
            // The second page of the chapter carries on after it.
            assertEquals(paragraph.joinToString(" "), first.paragraphs.last())
            val all = book.chapters.flatMap { it.paragraphs }.joinToString(" ")
            assertFalse(all, all.contains("ptg8274339"))
            assertFalse(all, all.contains("GAME MECHANICS"))
            assertEquals(2, book.chapters[1].paragraphs.size)
        } finally {
            file.delete()
            pictures.deleteRecursively()
        }
    }
}
