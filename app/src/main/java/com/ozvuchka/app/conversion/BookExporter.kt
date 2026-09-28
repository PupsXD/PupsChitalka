package com.ozvuchka.app.conversion

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** A picture ready to put into an exported book. */
class ExportPicture(val bytes: ByteArray, val mediaType: String, val extension: String)

/** Writes reflowable text exports from the same normalized chapters used by the reader. */
object BookExporter {
    /** [pictures] gives the stored picture by its file name, as JPEG, PNG or GIF; null leaves it out. */
    fun write(book: Book, format: String, output: OutputStream, pictures: (String) -> ExportPicture? = { null }) {
        when (format.lowercase()) {
            "epub" -> writeEpub(book, output, pictures)
            "fb2" -> writeFb2(book, output, pictures)
            else -> error("Unsupported export format: $format")
        }
    }

    private fun writeEpub(book: Book, output: OutputStream, pictures: (String) -> ExportPicture?) {
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

            // Pictures go in once each, under a safe name.
            val stored = LinkedHashMap<String, Pair<String, ExportPicture>>()
            fun pictureHref(name: String): String? {
                stored[name]?.let { return it.first }
                val picture = pictures(name) ?: return null
                val href = "images/pic${stored.size + 1}.${picture.extension}"
                stored[name] = href to picture
                zip.putNextEntry(ZipEntry("OEBPS/$href"))
                zip.write(picture.bytes)
                zip.closeEntry()
                return href
            }

            book.chapters.forEachIndexed { index, chapter ->
                val body = buildString {
                    append("<h1>${xml(chapter.title)}</h1>")
                    appendChapterHtml(chapter, ::pictureHref)
                }
                zip.add("OEBPS/chapter$index.xhtml", xhtml(chapter.title, body))
            }

            val language = languageOf(book)
            val modified = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withZone(ZoneOffset.UTC).format(Instant.now())
            val manifest = buildString {
                append("<item id=\"nav\" href=\"nav.xhtml\" media-type=\"application/xhtml+xml\" properties=\"nav\"/>")
                book.chapters.forEachIndexed { index, _ ->
                    append("<item id=\"chapter$index\" href=\"chapter$index.xhtml\" media-type=\"application/xhtml+xml\"/>")
                }
                stored.values.forEachIndexed { index, (href, picture) ->
                    append("<item id=\"picture$index\" href=\"$href\" media-type=\"${picture.mediaType}\"/>")
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
        }
    }

    /** Paragraphs as XHTML: headings, notes, captions, pictures and tables in their own elements. */
    private fun StringBuilder.appendChapterHtml(chapter: Chapter, pictureHref: (String) -> String?) {
        var inTable = false
        chapter.paragraphs.forEachIndexed { index, paragraph ->
            val style = chapter.styles[index]
            val row = style?.kind == ParagraphKind.TABLE_ROW || style?.kind == ParagraphKind.TABLE_HEADER
            if (inTable && !row) {
                append("</table>")
                inTable = false
            }
            when (style?.kind) {
                ParagraphKind.HEADING -> append("<h2>${xml(paragraph)}</h2>")
                ParagraphKind.SUBHEADING -> append("<h3>${xml(paragraph)}</h3>")
                ParagraphKind.NOTE -> append("<blockquote class=\"note\"><p>${xml(paragraph)}</p></blockquote>")
                ParagraphKind.CAPTION -> append("<p class=\"caption\">${xml(paragraph)}</p>")
                ParagraphKind.IMAGE -> style.image?.let(pictureHref)?.let { href ->
                    append("<div class=\"figure\"><img src=\"$href\" alt=\"\"/></div>")
                }
                ParagraphKind.TABLE_ROW, ParagraphKind.TABLE_HEADER -> {
                    if (!inTable) {
                        append("<table>")
                        inTable = true
                    }
                    val cell = if (style.kind == ParagraphKind.TABLE_HEADER) "th" else "td"
                    append("<tr>")
                    paragraph.split('\t').forEach { append("<$cell>${xml(it)}</$cell>") }
                    append("</tr>")
                }
                null -> append("<p>${xml(paragraph)}</p>")
            }
        }
        if (inTable) append("</table>")
    }

    private fun writeFb2(book: Book, output: OutputStream, pictures: (String) -> ExportPicture?) {
        val binaries = LinkedHashMap<String, Pair<String, ExportPicture>>()
        fun pictureId(name: String): String? {
            binaries[name]?.let { return it.first }
            val picture = pictures(name) ?: return null
            val id = "pic${binaries.size + 1}.${picture.extension}"
            binaries[name] = id to picture
            return id
        }
        val sections = book.chapters.joinToString("") { chapter ->
            buildString {
                append("<section><title><p>${xml(chapter.title)}</p></title>")
                var inTable = false
                chapter.paragraphs.forEachIndexed { index, paragraph ->
                    val style = chapter.styles[index]
                    val row = style?.kind == ParagraphKind.TABLE_ROW || style?.kind == ParagraphKind.TABLE_HEADER
                    if (inTable && !row) {
                        append("</table>")
                        inTable = false
                    }
                    when (style?.kind) {
                        ParagraphKind.HEADING, ParagraphKind.SUBHEADING -> append("<subtitle>${xml(paragraph)}</subtitle>")
                        ParagraphKind.NOTE -> append("<cite><p>${xml(paragraph)}</p></cite>")
                        ParagraphKind.CAPTION -> append("<p><emphasis>${xml(paragraph)}</emphasis></p>")
                        ParagraphKind.IMAGE -> style.image?.let(::pictureId)?.let { id -> append("<image l:href=\"#$id\"/>") }
                        ParagraphKind.TABLE_ROW, ParagraphKind.TABLE_HEADER -> {
                            if (!inTable) {
                                append("<table>")
                                inTable = true
                            }
                            val cell = if (style.kind == ParagraphKind.TABLE_HEADER) "th" else "td"
                            append("<tr>")
                            paragraph.split('\t').forEach { append("<$cell>${xml(it)}</$cell>") }
                            append("</tr>")
                        }
                        null -> append("<p>${xml(paragraph)}</p>")
                    }
                }
                if (inTable) append("</table>")
                append("</section>")
            }
        }
        val encoder = Base64.getMimeEncoder()
        val binaryXml = binaries.values.joinToString("") { (id, picture) ->
            "<binary id=\"$id\" content-type=\"${picture.mediaType}\">${encoder.encodeToString(picture.bytes)}</binary>"
        }
        val result = """<?xml version="1.0" encoding="UTF-8"?>
            <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
              <description><title-info>
                <genre>prose</genre>
                <author><first-name>${xml(book.author.ifBlank { "Неизвестный" })}</first-name><last-name></last-name></author>
                <book-title>${xml(book.title)}</book-title><lang>${languageOf(book)}</lang>
              </title-info></description>
              <body>$sections</body>$binaryXml
            </FictionBook>""".trimIndent()
        output.write(result.toByteArray(Charsets.UTF_8))
    }

    private fun xhtml(title: String, body: String) = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE html>
        <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
          <head><meta charset="UTF-8"/><title>${xml(title)}</title>
          <style>body{font-family:serif;line-height:1.55;margin:5%}p{text-indent:1.2em;margin:.65em 0}h1{text-align:center;margin:2em 0}h2,h3{margin:1.4em 0 .6em}
          blockquote.note{margin:1em 0;padding:.2em .8em;border-left:3px solid #999;font-size:.92em}blockquote.note p{text-indent:0}
          p.caption{text-indent:0;font-style:italic;font-size:.88em}div.figure{text-align:center;margin:1em 0}div.figure img{max-width:100%}
          table{border-collapse:collapse;margin:1em 0;font-size:.88em}td,th{border:1px solid #aaa;padding:.2em .4em;vertical-align:top}</style></head>
          <body>$body</body>
        </html>""".trimIndent()

    private fun ZipOutputStream.add(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun languageOf(book: Book): String =
        if (book.chapters.any { it.paragraphs.any { paragraph -> paragraph.any { char -> char in 'Ѐ'..'ӿ' } } }) "ru" else "en"

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
