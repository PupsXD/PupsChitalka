package com.ozvuchka.app.conversion

import com.ozvuchka.app.data.Book
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Writes reflowable text exports from the same normalized chapters used by the reader. */
object BookExporter {
    fun write(book: Book, format: String, output: OutputStream) {
        when (format.lowercase()) {
            "epub" -> writeEpub(book, output)
            "fb2" -> writeFb2(book, output)
            else -> error("Unsupported export format: $format")
        }
    }

    private fun writeEpub(book: Book, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            val mimetype = "application/epub+zip".toByteArray(Charsets.US_ASCII)
            val crc = CRC32().apply { update(mimetype) }
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetype.size.toLong()
                compressedSize = mimetype.size.toLong()
                this.crc = crc.value
            })
            zip.write(mimetype)
            zip.closeEntry()

            zip.add("META-INF/container.xml", """<?xml version="1.0" encoding="UTF-8"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
                </container>""".trimIndent())

            val language = languageOf(book)
            val modified = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now())
            val manifest = buildString {
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                book.chapters.forEachIndexed { index, _ ->
                    append("<item id=\"chapter$index\" href=\"chapter$index.xhtml\" media-type=\"application/xhtml+xml\"/>")
                }
            }
            val spine = buildString {
                book.chapters.forEachIndexed { index, _ -> append("<itemref idref=\"chapter$index\"/>") }
            }
            zip.add("OEBPS/content.opf", """<?xml version="1.0" encoding="UTF-8"?>
                <package xmlns="http://www.idpf.org/2007/opf" unique-identifier="bookid" version="3.0" xml:lang="$language">
                  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/">
                    <dc:identifier id="bookid">urn:uuid:${xml(book.id)}</dc:identifier>
                    <dc:title>${xml(book.title)}</dc:title>
                    <dc:creator>${xml(book.author.ifBlank { "Неизвестный автор" })}</dc:creator>
                    <dc:language>$language</dc:language>
                    <meta property="dcterms:modified">$modified</meta>
                  </metadata>
                  <manifest>$manifest</manifest>
                  <spine>$spine</spine>
                </package>""".trimIndent())

            val navItems = book.chapters.mapIndexed { index, chapter ->
                "<li><a href=\"chapter$index.xhtml\">${xml(chapter.title)}</a></li>"
            }.joinToString("")
            zip.add("OEBPS/nav.xhtml", xhtml(book.title, "<nav epub:type=\"toc\" id=\"toc\"><h1>Содержание</h1><ol>$navItems</ol></nav>"))
            book.chapters.forEachIndexed { index, chapter ->
                val body = buildString {
                    append("<h1>${xml(chapter.title)}</h1>")
                    chapter.paragraphs.forEach { append("<p>${xml(it)}</p>") }
                }
                zip.add("OEBPS/chapter$index.xhtml", xhtml(chapter.title, body))
            }
        }
    }

    private fun writeFb2(book: Book, output: OutputStream) {
        val sections = book.chapters.joinToString("") { chapter ->
            buildString {
                append("<section><title><p>${xml(chapter.title)}</p></title>")
                chapter.paragraphs.forEach { append("<p>${xml(it)}</p>") }
                append("</section>")
            }
        }
        val result = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
              <description><title-info>
                <genre>prose</genre>
                <author><first-name>${xml(book.author.ifBlank { "Неизвестный" })}</first-name><last-name></last-name></author>
                <book-title>${xml(book.title)}</book-title><lang>${languageOf(book)}</lang>
              </title-info></description>
              <body>$sections</body>
            </FictionBook>""".trimIndent()
        output.write(result.toByteArray(Charsets.UTF_8))
    }

    private fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><meta charset="UTF-8"/><title>${xml(title)}</title>
          <style>body{font-family:serif;line-height:1.55;margin:5%}p{text-indent:1.2em;margin:.65em 0}h1{text-align:center;margin:2em 0}</style></head>
          <body>$body</body>
        </html>""".trimIndent()

    private fun ZipOutputStream.add(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun languageOf(book: Book): String =
        if (book.chapters.any { it.paragraphs.any { paragraph -> paragraph.any { char -> char in '\u0400'..'\u04ff' } } }) "ru" else "en"

    private fun xml(value: String): String = buildString(value.length) {
        value.forEach { character ->
            when (character) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> if (character == '\n' || character == '\t' || character.code >= 0x20) append(character)
            }
        }
    }
}
