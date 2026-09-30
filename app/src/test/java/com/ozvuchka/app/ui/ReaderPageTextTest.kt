package com.ozvuchka.app.ui

import androidx.compose.ui.test.junit4.createComposeRule
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every letter of a chapter is drawn on exactly one page. Pagination measures lines in a column
 * and the page draws them in a column of its own; a pixel between the two wraps the lines apart,
 * and the words at the end of a page are shown twice or on no page at all. The text is laid out
 * for real (native graphics), on screens where the page margins rounded one way and the other.
 */
abstract class ReaderPageTextTest {
    @get:Rule
    val compose = createComposeRule()

    /** Long and short paragraphs of words of every length, so the lines end in all sorts of places. */
    private val paragraphs: List<String> = run {
        val words = ("она он и в не на что как это было лето лагерь вожатая девушка память письмо стыд желание " +
            "ночью комната сигарета музыка родители лавка общежитие воспоминание неуверенность экзистенциализм " +
            "«Снежная звезда» – т. д. 58-го года, двадцать самостоятельность одновременно преподавательница " +
            "Руан Ивто Бовуар Пруст Вирджиния Вульф").split(' ')
        val random = Random(58)
        List(70) {
            val length = random.nextInt(60, 1_600)
            buildString {
                while (this.length < length) {
                    append(words[random.nextInt(words.size)])
                    append(
                        when (random.nextInt(16)) {
                            0 -> ", "
                            1 -> ". "
                            2 -> ": "
                            else -> " "
                        },
                    )
                }
            }.trim().trimEnd(',', '.', ':').replaceFirstChar { it.uppercase() } + "."
        }
    }

    @Test
    fun everyLetterOfTheChapterIsOnExactlyOnePage() {
        val turns = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val state = ReaderUiState(
            bookId = "book",
            title = "Книга",
            chapterTitle = "Первая",
            chapterIndex = 0,
            chapterCount = 1,
            paragraphs = paragraphs,
        )
        compose.setContent { ReaderScreen(state, IdleReaderActions, turns) }
        val shown = compose.shownLetters(paragraphs, turns)
        assertTrue("a chapter this long takes many pages", shown.pages > 20)
        assertEquals(emptyList<String>(), shown.misshown(paragraphs))
    }
}

/** 440 dpi (density 2.75): the page was drawn a pixel narrower than measured, and words were lost. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h872dp-440dpi")
class ReaderPageTextAt440DpiTest : ReaderPageTextTest()

/** 408 dpi: the page was drawn a pixel wider than measured, and words were repeated. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w423dp-h941dp-408dpi")
class ReaderPageTextAt408DpiTest : ReaderPageTextTest()
