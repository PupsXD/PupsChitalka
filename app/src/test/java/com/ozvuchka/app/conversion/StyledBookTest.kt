package com.ozvuchka.app.conversion

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.LibraryStore
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import com.ozvuchka.app.importer.FolderImageSink
import com.ozvuchka.app.importer.ImageSinkTest
import com.ozvuchka.app.importer.readEpub
import com.ozvuchka.app.importer.readFb2
import com.ozvuchka.app.speech.SegmentPause
import com.ozvuchka.app.speech.chapterSegments
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Headings, notes, captions, pictures and tables survive storage, export and narration. */
class StyledBookTest {
    private val picture = ImageSinkTest.png(320, 200)
    private val book = Book(
        title = "Механики",
        author = "Автор",
        format = "PDF",
        chapters = listOf(
            Chapter(
                title = "Глава 1",
                paragraphs = listOf(
                    "Пулы и ресурсы",
                    "Пул собирает ресурсы.",
                    "",
                    "Рисунок 1. Пул",
                    "Совет: пул может быть пустым.",
                    "Настройка\tПобеды",
                    "Без настроек\t929",
                    "Текст дальше.",
                ),
                styles = mapOf(
                    0 to ParagraphStyle(ParagraphKind.HEADING),
                    2 to ParagraphStyle(ParagraphKind.IMAGE, "img1.webp", 320, 200),
                    3 to ParagraphStyle(ParagraphKind.CAPTION),
                    4 to ParagraphStyle(ParagraphKind.NOTE),
                    5 to ParagraphStyle(ParagraphKind.TABLE_HEADER),
                    6 to ParagraphStyle(ParagraphKind.TABLE_ROW),
                ),
            ),
        ),
    )

    private fun pictures(name: String): ExportPicture? = if (name == "img1.webp") ExportPicture(picture, "image/png", "png") else null

    @Test
    fun stylesAreStoredWithTheBook() {
        val styles = book.chapters.single().styles
        assertEquals(styles, LibraryStore.decodeStyles(LibraryStore.encodeStyles(styles)))
    }

    @Test
    fun epubExportKeepsLooksAndPictures() {
        roundTrip("epub") { file, sink -> readEpub(file, "fallback", sink) }
    }

    @Test
    fun fb2ExportKeepsLooksAndPictures() {
        roundTrip("fb2") { file, sink -> readFb2(file, "fallback", sink) }
    }

    private fun roundTrip(format: String, read: (File, FolderImageSink) -> Book) {
        val file = File.createTempFile("styled-", ".$format")
        val folder = Files.createTempDirectory("styled-pictures").toFile()
        try {
            file.outputStream().use { BookExporter.write(book, format, it, ::pictures) }
            val restored = read(file, FolderImageSink(folder)).chapters.single()
            val original = book.chapters.single()
            assertEquals(original.paragraphs, restored.paragraphs)
            val expected = original.styles.mapValues { (_, style) ->
                if (style.kind == ParagraphKind.IMAGE) ParagraphStyle(ParagraphKind.IMAGE, "img1.png", 320, 200) else style
            }.let { styles ->
                // FB2 has one kind of subtitle and marks captions only by emphasis.
                if (format == "fb2") {
                    styles.mapValues { (_, style) ->
                        when (style.kind) {
                            ParagraphKind.HEADING -> ParagraphStyle(ParagraphKind.SUBHEADING)
                            else -> style
                        }
                    }.filterValues { it.kind != ParagraphKind.CAPTION && it.kind != ParagraphKind.NOTE }
                } else styles
            }
            assertEquals(expected, restored.styles)
            assertEquals(picture.toList(), File(folder, "img1.png").readBytes().toList())
        } finally {
            file.delete()
            folder.deleteRecursively()
        }
    }

    @Test
    fun narrationReadsTablesCellByCellAndPausesAfterHeadings() {
        val chapter = book.chapters.single()
        val segments = chapterSegments(0, null, chapter.paragraphs, headings = setOf(0))
        assertEquals(SegmentPause.TITLE, segments.first { it.paragraph == 0 }.pause)
        val row = segments.filter { it.paragraph == 6 }
        assertEquals(listOf("Без настроек", "929"), row.map { it.text })
        assertEquals(listOf(SegmentPause.TURN, SegmentPause.PARAGRAPH), row.map { it.pause })
        // Offsets point at the cells in the row's text, for highlighting.
        assertEquals("929", chapter.paragraphs[6].substring(row[1].start, row[1].end))
        // The picture is silent.
        assertEquals(emptyList<Int>(), segments.filter { it.paragraph == 2 }.map { it.paragraph })
    }
}
