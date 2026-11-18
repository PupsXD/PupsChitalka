package com.ozvuchka.app.importer

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The whole-book import: the declared FB2 link, what its bytes must be, and the reuse of [readFb2]. */
class WebBookImporterTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/importer/$name")) { "нет фикстуры $name" }
            .readBytes().toString(Charsets.UTF_8)

    private val bookPageUrl = "https://litmir.club/bd/?b=149221"

    // ---------- Finding the page and the declared file ----------

    @Test
    fun theBookPageIsFoundFromAChapterPage() {
        val document = Jsoup.parse(fixture("litmir-chapter.html"), "https://litmir.club/br/?b=149221&p=2")
        assertEquals(bookPageUrl, WebBookImporter.findBookPageUrl(document, "https://litmir.club/br/?b=149221&p=2"))
        assertTrue(WebBookImporter.isBookPageUrl(bookPageUrl))
        assertFalse(WebBookImporter.isBookPageUrl("https://litmir.club/br/?b=149221&p=2"))
    }

    @Test
    fun theDeclaredFb2FileIsChosenAmongTheFormats() {
        val document = Jsoup.parse(fixture("litmir-book-page.html"), bookPageUrl)
        // The first file on the page is the EPUB; the FB2 row names its own id.
        assertEquals(
            "https://litmir.club/BookFileDownloadLink/?id=1860807&inline=0",
            WebBookImporter.findFb2DownloadUrl(document, bookPageUrl),
        )
    }

    @Test
    fun aBookPageWithoutFb2GivesNoFile() {
        val onlyEpub = """
            <div itemtype="http://schema.org/Book">
              <input id="radio1" type="radio" downloadurl="/BookFileDownloadLink/?id=1&amp;inline=0" />
              <label for="radio1">epub</label>
            </div>
        """.trimIndent()
        assertNull(WebBookImporter.findFb2DownloadUrl(Jsoup.parse(onlyEpub, bookPageUrl), bookPageUrl))
    }

    @Test
    fun aDeclaredFileOnAnotherSiteIsNotFollowed() {
        val foreign = """
            <div itemtype="http://schema.org/Book">
              <input id="radio1" type="radio" downloadurl="https://files.example/book.fb2.zip" />
              <label for="radio1">fb2</label>
            </div>
        """.trimIndent()
        assertNull(WebBookImporter.findFb2DownloadUrl(Jsoup.parse(foreign, bookPageUrl), bookPageUrl))
    }

    @Test
    fun theBookPageMetadataNamesTheBookAndItsAuthor() {
        val page = WebBookImporter.bookPageMetadata(Jsoup.parse(fixture("litmir-book-page.html"), bookPageUrl))
        assertEquals("Кракен", page.title)
        assertEquals("Мьевиль Чайна", page.author)
    }

    @Test
    fun onlyReaderSitesWithAFileListAreOffered() {
        assertTrue(WebBookImporter.supports("https://litmir.club/br/?b=149221&p=2"))
        assertTrue(WebBookImporter.supports("https://www.litmir.me/bd/?b=1"))
        assertFalse(WebBookImporter.supports("https://author.today/reader/1"))
        assertFalse(WebBookImporter.supports("http://litmir.club/br/?b=149221"))
        assertFalse(WebBookImporter.supports("не ссылка"))
    }

    // ---------- What the server actually sent ----------

    @Test
    fun theDownloadedBytesDecideTheFormat() {
        assertTrue(WebBookImporter.looksLikeFb2(fb2Zip("Книга", "Иван", "Петров")))
        assertTrue(
            WebBookImporter.looksLikeFb2(
                "<?xml version=\"1.0\"?><FictionBook><body/></FictionBook>".toByteArray(),
            ),
        )
        // An HTML answer, a login page or a challenge page is not a book, whatever it is named.
        assertFalse(WebBookImporter.looksLikeFb2("<html><body>Войдите на сайт</body></html>".toByteArray()))
        assertFalse(WebBookImporter.looksLikeFb2(ByteArray(0)))
    }

    @Test
    fun theAnnouncedZipIsReadByTheExistingFb2Reader() {
        val file = folder.newFile("announced.fb2.zip").apply { writeBytes(fb2Zip("Кракен", "Чайна", "Мьевиль")) }
        val book = readFb2(file, "Книга с сайта")
        assertEquals("Кракен", book.title)
        assertEquals("Чайна Мьевиль", book.author)
        assertTrue(book.chapters.isNotEmpty())
        assertTrue(book.chapters.first().paragraphs.any { it.contains("первой главы") })
    }

    @Test
    fun aZipWithoutAnFb2InsideIsAFailedBook() {
        val file = folder.newFile("not-a-book.fb2.zip").apply {
            ZipOutputStream(outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("readme.txt"))
                zip.write("nothing here".toByteArray())
                zip.closeEntry()
            }
        }
        val error = runCatching { readFb2(file, "Книга") }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertTrue(error!!.message!!.contains("FB2"))
    }

    // ---------- Identity is checked, not assumed ----------

    @Test
    fun anAuthorWrittenInAnotherOrderIsStillTheSamePerson() {
        val book = Book(
            title = "Кракен",
            author = "Чайна Мьевиль",
            format = "FB2",
            chapters = listOf(Chapter("Глава", listOf("Текст"))),
        )
        assertTrue(WebBookImporter.identityWarnings("Кракен", "Мьевиль Чайна", book).isEmpty())
    }

    @Test
    fun aDifferentBookOrAuthorIsReportedButStillImported() {
        val book = Book(
            title = "Кракен",
            author = "Чайна Мьевиль",
            format = "FB2",
            chapters = listOf(Chapter("Глава", listOf("Текст"))),
        )
        val warnings = WebBookImporter.identityWarnings("Моби Дик", "Герман Мелвилл", book)
        assertEquals(2, warnings.size)
        assertTrue(warnings[0].contains("Моби Дик"))
        assertTrue(warnings[1].contains("Мелвилл"))
        // A missing page field is not a contradiction.
        assertTrue(WebBookImporter.identityWarnings("", "", book).isEmpty())
    }

    /** A small FB2 in a ZIP, the way reader sites publish it; no book text from the internet is stored. */
    private fun fb2Zip(title: String, firstName: String, lastName: String): ByteArray {
        val fb2 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description>
                <title-info>
                  <book-title>$title</book-title>
                  <author><first-name>$firstName</first-name><last-name>$lastName</last-name></author>
                </title-info>
              </description>
              <body>
                <section>
                  <title><p>Глава первая</p></title>
                  <p>Первый абзац первой главы, короткий и спокойный.</p>
                  <p>Второй абзац первой главы, не длиннее первого.</p>
                </section>
              </body>
            </FictionBook>
        """.trimIndent()
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("book.fb2"))
            zip.write(fb2.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return bytes.toByteArray()
    }
}
