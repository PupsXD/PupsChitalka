package com.ozvuchka.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.SearchHit
import com.ozvuchka.app.speech.CastMember
import com.ozvuchka.app.speech.SpeechRole
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A character read with the wrong voice is fixed from the list of the book's characters. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h800dp")
class CastSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val chosen = mutableListOf<Pair<String, SpeechRole?>>()

    private val actions = object : ReaderActions {
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
        override fun setCharacterGender(name: String, gender: SpeechRole?) {
            chosen += name to gender
        }
        override fun addAnnotation(annotation: Annotation) = Unit
        override fun updateAnnotation(annotation: Annotation) = Unit
        override fun removeAnnotation(id: String) = Unit
        override fun jumpTo(chapter: Int, paragraph: Int, offset: Int, mark: IntRange?) = Unit
        override suspend fun search(query: String): List<SearchHit> = emptyList()
        override fun translate(text: String) = Unit
        override fun copyText(text: String) = Unit
        override fun shareQuote(text: String) = Unit
    }

    private val state = ReaderUiState(
        bookId = "book",
        title = "Книга",
        chapterTitle = "Глава 1",
        chapterIndex = 0,
        chapterCount = 1,
        paragraphs = listOf("— Вы оба невыносимы, — говорит Вэнь Цин."),
        characters = listOf(
            CastMember("Лань Чжань", SpeechRole.MALE, 50),
            CastMember("Вэнь Цин", SpeechRole.MALE, 30),
            CastMember("Облачные Глубины", null, 12),
        ),
        voicesByGender = true,
    )

    @Test
    fun theListOpensACharacter() {
        val picked = mutableListOf<String>()
        compose.setContent { CastSheet(state, actions, onPick = { picked += it.name }) {} }
        compose.onNodeWithText("Лань Чжань").assertExists()
        // Names whose gender the text never showed wait behind a button.
        compose.onNodeWithText("Облачные Глубины").assertDoesNotExist()
        // A click through semantics: Robolectric's taps do not reach rows of a sheet that slides in.
        compose.onNodeWithText("Вэнь Цин").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
        assertEquals(listOf("Вэнь Цин"), picked)
        compose.onNodeWithText("Ещё имена, пол не ясен: 1").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Облачные Глубины").assertExists()
    }

    @Test
    fun theDialogSetsTheVoice() {
        compose.setContent { CharacterVoiceDialog(CastMember("Вэнь Цин", SpeechRole.MALE, 30), actions) {} }
        compose.onNodeWithText("Женским").performClick()
        compose.waitForIdle()
        assertEquals(listOf("Вэнь Цин" to SpeechRole.FEMALE), chosen)
    }
}
