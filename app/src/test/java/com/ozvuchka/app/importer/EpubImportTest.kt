package com.ozvuchka.app.importer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class EpubImportTest {
    private fun chapterXhtml(head: String, paragraphs: Int) = """<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
$head
<link rel="stylesheet" href="style.css" type="text/css"/>
</head>
<body class="z"><span>${(1..paragraphs).joinToString("") { """<p class="p1">Абзац номер $it. ${"Слова, слова. ".repeat(30)}</p>""" }}</span></body>
</html>"""

    private fun epub(chapters: List<String>): File {
        val file = File.createTempFile("chapters-", ".epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put(
                "META-INF/container.xml",
                """<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0"><rootfiles>
                <rootfile full-path="OPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""",
            )
            val ids = chapters.indices.map { "c$it" }
            put(
                "OPS/content.opf",
                """<package xmlns="http://www.idpf.org/2007/opf" version="2.0"><metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Книга</dc:title><dc:creator>Автор</dc:creator></metadata>
                <manifest>${ids.joinToString("") { """<item id="$it" href="$it.xhtml" media-type="application/xhtml+xml"/>""" }}</manifest>
                <spine>${ids.joinToString("") { """<itemref idref="$it"/>""" }}</spine></package>""",
            )
            chapters.forEachIndexed { index, xhtml -> put("OPS/c$index.xhtml", xhtml) }
        }
        return file
    }

    /** Books converted from FB2 put `<title/>` in every chapter's head; the HTML parser then read the chapter as the title. */
    @Test
    fun emptyRawTextElementsInTheHeadDoNotSwallowTheChapter() {
        val heads = listOf(
            "<title/>",
            "<title />",
            "<TITLE/>",
            "<title></title>",
            "<title>Книга</title>",
            """<script src="js/a.js" type="text/javascript"/>""",
            """<style type="text/css"/>""",
        )
        val file = epub(heads.map { chapterXhtml(it, paragraphs = 60) })
        try {
            val book = readEpub(file, "fallback")
            assertEquals(heads.size, book.chapters.size)
            book.chapters.forEachIndexed { index, chapter ->
                assertEquals("head ${heads[index]}", 60, chapter.paragraphs.size)
                assertTrue(chapter.paragraphs.first().startsWith("Абзац номер 1."))
                assertTrue(chapter.paragraphs.last().startsWith("Абзац номер 60."))
            }
        } finally {
            file.delete()
        }
    }
}
