package com.ozvuchka.app.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import com.ozvuchka.app.importer.readEpub
import com.ozvuchka.app.importer.readFb2
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A book from the disk, read cover to cover: every letter of every chapter is drawn on exactly one
 * page. Skipped unless REAL_BOOK names an .epub or .fb2 file; REAL_BOOK_DPI sets the screen density
 * (440 when unset; the screen stays 1078 pixels wide, like most phones). What each chapter holds,
 * and every word drawn on no page or on two, goes to the test's output.
 *
 *     REAL_BOOK="C:/Books/book.epub" REAL_BOOK_DPI=416 ./gradlew :app:testDebugUnitTest --tests '*RealBookPagesTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h872dp-440dpi")
class RealBookPagesTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun everyLetterOfTheBookIsOnExactlyOnePage() {
        val file = File(System.getenv("REAL_BOOK").orEmpty())
        assumeTrue("REAL_BOOK is not set", file.isFile)
        val book = when (file.extension.lowercase()) {
            "epub" -> readEpub(file, file.nameWithoutExtension)
            "fb2" -> readFb2(file, file.nameWithoutExtension)
            else -> error("REAL_BOOK must be an .epub or .fb2 file")
        }
        val density = (System.getenv("REAL_BOOK_DPI")?.toFloatOrNull() ?: 440f) / 160f
        val turns = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        var chapterIndex by mutableIntStateOf(0)
        compose.setContent {
            val chapter = book.chapters[chapterIndex]
            val state = ReaderUiState(
                bookId = "real-book",
                title = book.title,
                chapterTitle = chapter.title,
                chapterIndex = chapterIndex,
                chapterCount = book.chapters.size,
                paragraphs = chapter.paragraphs,
                styles = chapter.styles,
            )
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                ReaderScreen(state, IdleReaderActions, turns)
            }
        }
        println("«${book.title}», ${book.format}, ${book.chapters.size} chapters, ${density * 160} dpi")
        val problems = mutableListOf<String>()
        book.chapters.forEachIndexed { index, chapter ->
            chapterIndex = index
            val shown = compose.shownLetters(chapter.paragraphs, turns, pixelsPerDp = density)
            val misshown = shown.misshown(chapter.paragraphs, chapter.styles)
            println(
                "Chapter ${index + 1} «${chapter.title}»: ${chapter.paragraphs.size} paragraphs, " +
                    "${chapter.paragraphs.sumOf { it.length }} letters, ${shown.pages} pages, ${misshown.size} misdrawn",
            )
            misshown.forEach { println("    $it") }
            problems += misshown.map { "chapter ${index + 1}, $it" }
        }
        assertEquals(emptyList<String>(), problems)
    }
}
