package com.ozvuchka.app.speech

import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.importer.readEpub
import com.ozvuchka.app.importer.readFb2
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * A book from the disk, voiced by gender the way narration does it: every paragraph with the voice
 * of each line — M, F, ? for the narrator reading a line — goes to the file named by
 * REAL_BOOK_ROLES (or the test's output), for reading the book's dialogue through and finding the
 * lines that get the wrong voice. Skipped unless REAL_BOOK names an .epub, .fb2 or .txt file.
 *
 *     REAL_BOOK="C:/Books/book.epub" REAL_BOOK_ROLES="C:/Temp/roles.txt" ./gradlew :app:testDebugUnitTest --tests '*RealBookSpeakersTest*'
 */
class RealBookSpeakersTest {
    @Test
    fun voicesOfTheBook() {
        val file = File(System.getenv("REAL_BOOK").orEmpty())
        assumeTrue("REAL_BOOK is not set", file.isFile)
        val book = when (file.extension.lowercase()) {
            "epub" -> readEpub(file, file.nameWithoutExtension)
            "fb2" -> readFb2(file, file.nameWithoutExtension)
            // A story saved as text, one paragraph a line.
            "txt" -> Book(title = file.nameWithoutExtension, format = "txt", chapters = listOf(Chapter(file.nameWithoutExtension, file.readLines().filter { it.isNotBlank() })))
            else -> error("REAL_BOOK must be an .epub, .fb2 or .txt file")
        }
        val cast = Cast.learn(book.chapters.flatMap { it.paragraphs })
        val out = StringBuilder()
        val counts = HashMap<SpeechRole, Int>()
        out.appendLine("«${book.title}», ${book.chapters.size} chapters")
        out.appendLine("Cast: " + cast.members(5).take(40).joinToString { "${it.name} ${it.gender?.let(::mark) ?: "-"} ${it.mentions}" })
        book.chapters.forEachIndexed { chapterIndex, chapter ->
            out.appendLine()
            out.appendLine("=== ${chapterIndex + 1}. ${chapter.title}")
            val headings = chapter.styles.filterValues { it.kind == ParagraphKind.HEADING || it.kind == ParagraphKind.SUBHEADING }.keys
            val segments = chapterSegments(chapterIndex, chapter.title, chapter.paragraphs, splitDialogue = true, headings = headings, cast = cast)
            chapter.paragraphs.forEachIndexed { index, paragraph ->
                if (paragraph.isBlank()) return@forEachIndexed
                val roles = segments.filter { it.paragraph == index }.map { it.role }.filter { it != SpeechRole.NARRATOR }.toSet()
                val role = roles.singleOrNull()
                if (role != null) counts.merge(role, 1, Int::plus)
                val label = when {
                    roles.isEmpty() -> "  "
                    roles.size > 1 -> "!!"
                    else -> mark(role!!)
                }
                out.appendLine("$label #$index ${paragraph.trim()}")
            }
        }
        println("Lines: " + counts.entries.joinToString { "${mark(it.key)} ${it.value}" })
        val target = System.getenv("REAL_BOOK_ROLES")
        if (target.isNullOrBlank()) println(out) else File(target).writeText(out.toString())
    }

    private fun mark(role: SpeechRole) = when (role) {
        SpeechRole.MALE -> "M"
        SpeechRole.FEMALE -> "F"
        SpeechRole.SPEECH -> "?"
        else -> role.name
    }
}
