package com.ozvuchka.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.ParagraphKind
import com.ozvuchka.app.data.ParagraphStyle
import com.ozvuchka.app.data.SearchHit
import com.ozvuchka.app.speech.SpeechRole
import kotlin.math.abs
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertEquals

private fun SemanticsNode.text() = config[SemanticsProperties.Text].joinToString("") { it.text }

/**
 * Turns every page of the chapter a [ReaderScreen] shows, from its first page to its last, and
 * counts how many times each letter of [paragraphs] is drawn: the lines each page's text layout
 * shows within its height. The screen must page on [turns]; the counts mean something only with
 * real text layout (`@GraphicsMode(NATIVE)`). [pixelsPerDp] is the density the screen is drawn at.
 */
internal fun ComposeContentTestRule.shownLetters(
    paragraphs: List<String>,
    turns: MutableSharedFlow<Int>,
    margin: ReaderMargin = ReaderTypography().margin,
    pixelsPerDp: Float = density.density,
): ShownLetters {
    waitForIdle()
    val screenWidth = onRoot().fetchSemanticsNode().size.width
    // Running text starts at the page margin; the reader's bars and the page number do not.
    val column = margin.horizontalDp * pixelsPerDp
    val shown = paragraphs.map { IntArray(it.length) }
    var paragraph = 0
    var page = 0
    var pageCount: Int
    do {
        // The pager keeps the pages on either side ready, off the screen.
        val onScreen = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes()
            .filter { it.positionInRoot.x >= 0f && it.positionInRoot.x < screenWidth }
        val number = onScreen.firstNotNullOf { Regex("^(\\d+) / (\\d+)$").matchEntire(it.text()) }
        assertEquals("the page after page $page", page + 1, number.groupValues[1].toInt())
        page = number.groupValues[1].toInt()
        pageCount = number.groupValues[2].toInt()
        for (node in onScreen.filter { abs(it.positionInRoot.x - column) <= 1f }.sortedBy { it.positionInRoot.y }) {
            val text = node.text()
            // A block holds the rest of its paragraph; the page shows the lines that fit.
            val index = (paragraph until minOf(paragraphs.size, paragraph + 8))
                .firstOrNull { text.isNotEmpty() && paragraphs[it].trimEnd().endsWith(text) } ?: continue
            paragraph = index
            val layouts = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
            val layout = layouts.single()
            val lines = (0 until layout.lineCount).count { layout.getLineBottom(it) <= layout.size.height + 0.5f }
            if (lines == 0) continue
            val start = paragraphs[index].trimEnd().length - text.length
            for (offset in start until start + layout.getLineEnd(lines - 1, visibleEnd = true)) shown[index][offset]++
        }
        turns.tryEmit(1)
        waitForIdle()
    } while (page < pageCount)
    return ShownLetters(shown, pageCount)
}

/** How many times each letter of each paragraph was drawn, and on how many pages. */
internal class ShownLetters(val counts: List<IntArray>, val pages: Int) {
    /**
     * Letters drawn on no page or on two, one line per run of them, with the words around it. Only
     * running text and headings are checked: notes, table cells and pictures sit off the text column.
     */
    fun misshown(paragraphs: List<String>, styles: Map<Int, ParagraphStyle> = emptyMap()): List<String> =
        misshownLetters(paragraphs, counts, styles)
}

private val columnKinds = setOf(null, ParagraphKind.HEADING, ParagraphKind.SUBHEADING, ParagraphKind.CAPTION)

private fun misshownLetters(paragraphs: List<String>, shown: List<IntArray>, styles: Map<Int, ParagraphStyle>): List<String> = buildList {
    paragraphs.forEachIndexed { index, text ->
        if (styles[index]?.kind !in columnKinds) return@forEachIndexed
        var offset = 0
        while (offset < text.length) {
            val times = shown[index][offset]
            if (times == 1 || text[offset].isWhitespace()) {
                offset++
                continue
            }
            var end = offset
            while (end < text.length && (shown[index][end] == times || text[end].isWhitespace())) end++
            add(
                "paragraph $index, letters $offset–${end - 1} shown $times times: «" +
                    text.substring((offset - 30).coerceAtLeast(0), offset) + "[" + text.substring(offset, end) + "]" +
                    text.substring(end, (end + 30).coerceAtMost(text.length)) + "»",
            )
            offset = end
        }
    }
}

/** A reader whose buttons do nothing, for tests that only look at the pages. */
internal object IdleReaderActions : ReaderActions {
    override fun back() = Unit
    override fun changeChapter(index: Int, progress: Float) = Unit
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
    override fun loadCharacters() = Unit
    override fun setCharacterGender(name: String, gender: SpeechRole?) = Unit
    override fun addAnnotation(annotation: Annotation) = Unit
    override fun updateAnnotation(annotation: Annotation) = Unit
    override fun removeAnnotation(id: String) = Unit
    override fun jumpTo(chapter: Int, paragraph: Int, offset: Int, mark: IntRange?) = Unit
    override suspend fun search(query: String): List<SearchHit> = emptyList()
    override fun translate(text: String) = Unit
    override fun copyText(text: String) = Unit
    override fun shareQuote(text: String) = Unit
}
