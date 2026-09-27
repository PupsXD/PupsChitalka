package com.ozvuchka.app.conversion

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.importer.readEpub
import com.ozvuchka.app.importer.readFb2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

class BookRoundTripTest {
    private val sample = Book(
        title = "Ветер & город",
        author = "Анна <Б>",
        format = "PDF",
        chapters = listOf(
            Chapter("Глава 1", listOf("Он сказал: «Привет!»", "Пять < шести & семь > трёх.")),
            Chapter("Глава 2", listOf("Вторая глава без потерь.")),
        ),
    )

    @Test fun epubExportCanBeImportedAgain() {
        val file = File.createTempFile("roundtrip-", ".epub")
        try {
            file.outputStream().use { BookExporter.write(sample, "epub", it) }
            ZipFile(file).use { zip ->
                assertEquals(ZipEntry.STORED, zip.getEntry("mimetype").method)
                assertEquals("application/epub+zip", zip.getInputStream(zip.getEntry("mimetype"))
                    .bufferedReader().use { it.readText() })
                assertTrue(zip.getEntry("OEBPS/nav.xhtml") != null)
            }
            val restored = readEpub(file, "fallback")
            assertEquals(sample.title, restored.title)
            assertEquals(sample.author, restored.author)
            assertEquals(sample.chapters, restored.chapters)
        } finally {
            file.delete()
        }
    }

    @Test fun fb2ExportCanBeImportedAgain() {
        val file = File.createTempFile("roundtrip-", ".fb2")
        try {
            file.outputStream().use { BookExporter.write(sample, "fb2", it) }
            val restored = readFb2(file, "fallback")
            assertEquals(sample.title, restored.title)
            assertEquals(sample.chapters, restored.chapters)
        } finally {
            file.delete()
        }
    }
}
