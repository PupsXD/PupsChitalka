package com.ozvuchka.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.SearchHit
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A swipe past the edge of a chapter turns into the next or the previous chapter. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class ReaderSwipeTest {
    @get:Rule
    val compose = createComposeRule()

    private val opened = mutableListOf<Pair<Int, Float>>()

    private val actions = object : ReaderActions {
        override fun back() = Unit
        override fun changeChapter(index: Int, progress: Float) {
            opened += index to progress
        }
        override fun readingProgressChanged(overall: Float) = Unit
        override fun chromeVisibilityChanged(visible: Boolean) = Unit
        override fun playPause() = Unit
        override fun readFrom(paragraph: Int, offset: Int) = Unit
        override fun nextSentence() = Unit
        override fun previousSentence() = Unit
        override fun stopNarration() = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun setSleepTimer(minutes: Int) = Unit
        override fun openVoices() = Unit
        override fun typographyChanged(typography: ReaderTypography) = Unit
        override fun themeChanged(theme: ReaderTheme) = Unit
        override fun volumeKeysChanged(enabled: Boolean) = Unit
        override fun keepScreenOnChanged(enabled: Boolean) = Unit
        override fun highlightWordsChanged(enabled: Boolean) = Unit
        override fun autoLoadWebChaptersChanged(enabled: Boolean) = Unit
        override fun brightnessChanged(level: Float?, final: Boolean) = Unit
        override fun warmLightChanged(level: Float) = Unit
        override fun export(format: String) = Unit
        override fun importNextChapter() = Unit
        override fun pronunciationOf(word: String): PronunciationUi? = null
        override fun pronunciations(): List<PronunciationUi> = emptyList()
        override fun savePronunciation(word: String, spoken: String, everyBook: Boolean) = Unit
        override fun removePronunciation(word: String) = Unit
        override fun previewPronunciation(word: String, spoken: String, sentence: String, language: String) = Unit
        override fun addAnnotation(annotation: Annotation) = Unit
        override fun updateAnnotation(annotation: Annotation) = Unit
        override fun removeAnnotation(id: String) = Unit
        override fun jumpTo(chapter: Int, paragraph: Int, offset: Int, mark: IntRange?) = Unit
        override suspend fun search(query: String): List<SearchHit> = emptyList()
        override fun translate(text: String) = Unit
        override fun copyText(text: String) = Unit
        override fun shareQuote(text: String) = Unit
    }

    /** The first chapter runs over many pages; the others fit on one. */
    private fun paragraphs(index: Int) = if (index == 0) {
        List(60) { "Длинный абзац первой главы номер ${it + 1}, в котором достаточно слов, чтобы занять несколько строк на странице." }
    } else {
        listOf("Короткий абзац главы ${index + 1}.")
    }

    private fun chapter(index: Int) = ChapterContent(index, "Глава ${index + 1}", paragraphs(index))

    private fun state(index: Int) = ReaderUiState(
        bookId = "book",
        title = "Книга",
        chapterTitle = "Глава ${index + 1}",
        chapterIndex = index,
        chapterCount = 3,
        paragraphs = paragraphs(index),
        previousChapter = if (index > 0) chapter(index - 1) else null,
        nextChapter = if (index < 2) chapter(index + 1) else null,
    )

    @Test
    fun swipingPastTheLastPageOpensTheNextChapterAtItsStart() {
        compose.setContent { ReaderScreen(state(1), actions, emptyFlow()) }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(listOf(2 to 0f), opened)
    }

    @Test
    fun swipingBackFromTheFirstPageOpensThePreviousChapterAtItsLastPage() {
        compose.setContent { ReaderScreen(state(1), actions, emptyFlow()) }
        compose.onRoot().performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertEquals(1, opened.size)
        assertEquals(0, opened.single().first)
        // The previous chapter opens where its last page starts, not at its beginning.
        assertTrue(opened.single().second > 0f)
    }

    @Test
    fun theLastChapterStaysPut() {
        compose.setContent { ReaderScreen(state(2), actions, emptyFlow()) }
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertEquals(emptyList<Pair<Int, Float>>(), opened)
    }
}
