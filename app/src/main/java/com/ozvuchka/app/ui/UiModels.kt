package com.ozvuchka.app.ui

import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.SearchHit
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.ModelInstallState
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.SystemEngineInfo
import com.ozvuchka.app.speech.SystemVoiceInfo
import com.ozvuchka.app.speech.VoiceCatalog
import com.ozvuchka.app.speech.VoiceChoice

/** The small, storage-independent model shown in the library. */
data class LibraryBookUi(
    val id: String,
    val title: String,
    val author: String = "Неизвестный автор",
    val format: String = "EPUB",
    val progress: Float = 0f,
    val chapterTitle: String = "",
    val lastOpenedAt: Long = 0L,
)

enum class ReaderTheme {
    LIGHT,
    SEPIA,
    DARK,

    /** True black for OLED screens: the pixels are off, which saves battery at night. */
    BLACK,
    ;

    val isDark: Boolean get() = this == DARK || this == BLACK
}

enum class ReaderMargin(val horizontalDp: Int, val label: String) {
    NARROW(16, "Узкие"),
    NORMAL(26, "Обычные"),
    WIDE(40, "Широкие"),
}

data class ReaderTypography(
    val fontSizeSp: Float = 19f,
    val useSerif: Boolean = true,
    val lineSpacing: Float = 1.55f,
    val justify: Boolean = true,
    val paragraphIndent: Boolean = true,
    val margin: ReaderMargin = ReaderMargin.NORMAL,
)

/** What the reader needs to know about narration of the book on screen. */
data class ReaderNarrationUi(
    val active: Boolean = false,
    val playing: Boolean = false,
    val preparing: Boolean = false,
    /** Paragraph of the visible chapter being spoken; -1 for the chapter title, null elsewhere. */
    val paragraphIndex: Int? = null,
    val textOffset: Int = 0,
    val textLength: Int = 0,
    /** The word being spoken, for voices that report words; -1 when unknown or turned off. */
    val wordOffset: Int = -1,
    val wordLength: Int = 0,
    val speed: Float = 1f,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val voiceReady: Boolean = false,
    val voiceLabel: String = "",
)

/** All reader settings are supplied by the caller so they can be persisted between launches. */
data class ReaderUiState(
    val bookId: String,
    val title: String,
    val author: String = "",
    val chapterTitle: String,
    val chapterIndex: Int,
    val chapterCount: Int,
    val chapterTitles: List<String> = emptyList(),
    val paragraphs: List<String>,
    val language: String = "ru",
    val overallProgress: Float = 0f,
    val chapterProgress: Float = 0f,
    val typography: ReaderTypography = ReaderTypography(),
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val narration: ReaderNarrationUi = ReaderNarrationUi(),
    val hasNextWebChapter: Boolean = false,
    val volumeKeysTurnPages: Boolean = true,
    val keepScreenOn: Boolean = true,
    val highlightWords: Boolean = true,
    /** Bookmarks and highlights of the whole book. */
    val annotations: List<Annotation> = emptyList(),
    /** A request to show a place in this chapter: a bookmark, a quote or a search hit. */
    val jump: ReaderJump? = null,
    /** How many pronunciations the reader saved (for this book and for all books). */
    val pronunciationCount: Int = 0,
    /** Chapters come from a website, one by one. */
    val isWebBook: Boolean = false,
    val autoLoadWebChapters: Boolean = true,
)

/** Shows [paragraph] at [offset]; [mark] briefly highlights a search hit. A new [id] repeats a jump. */
data class ReaderJump(val id: Long, val chapter: Int, val paragraph: Int, val offset: Int, val mark: IntRange? = null)

/** A saved pronunciation as listed in the reader: «замок» → «за́мок», for this book or every book. */
data class PronunciationUi(val word: String, val spoken: String, val everyBook: Boolean)

interface ReaderActions {
    fun back()
    fun changeChapter(index: Int)
    /** Progress across the whole book, from 0f to 1f. */
    fun readingProgressChanged(overall: Float)
    fun chromeVisibilityChanged(visible: Boolean)
    fun playPause()
    fun readFrom(paragraph: Int, offset: Int)
    fun nextSentence()
    fun previousSentence()
    fun stopNarration()
    fun setSpeed(speed: Float)
    fun setSleepTimer(minutes: Int)
    fun openVoices()
    fun typographyChanged(typography: ReaderTypography)
    fun themeChanged(theme: ReaderTheme)
    fun volumeKeysChanged(enabled: Boolean)
    fun keepScreenOnChanged(enabled: Boolean)
    fun highlightWordsChanged(enabled: Boolean)
    fun autoLoadWebChaptersChanged(enabled: Boolean)
    fun export(format: String)
    fun importNextChapter()

    // Pronunciation fixes.
    fun pronunciationOf(word: String): PronunciationUi?
    fun pronunciations(): List<PronunciationUi>
    fun savePronunciation(word: String, spoken: String, everyBook: Boolean)
    fun removePronunciation(word: String)
    fun previewPronunciation(word: String, spoken: String, sentence: String, language: String)

    // Bookmarks, highlights and notes.
    fun addAnnotation(annotation: Annotation)
    fun updateAnnotation(annotation: Annotation)
    fun removeAnnotation(id: String)
    fun jumpTo(chapter: Int, paragraph: Int, offset: Int, mark: IntRange? = null)
    suspend fun search(query: String): List<SearchHit>

    // Text actions.
    fun translate(text: String)
    fun copyText(text: String)
    fun shareQuote(text: String)
}

/** Voice settings for both languages, downloads and installed system engines. */
data class VoiceSettingsUi(
    val russianVoice: VoiceChoice,
    val englishVoice: VoiceChoice,
    /** What the reader shows as the current voice: «RuVoice · Xenia», «Supertonic · Женский 1». */
    val russianVoiceLabel: String = "",
    val englishVoiceLabel: String = "",
    val speed: Float,
    val pauseScale: Float,
    val supertonicSteps: Int,
    val preferFullModels: Boolean,
    val installedModels: Set<SpeechModel> = emptySet(),
    val installStates: Map<SpeechModel, ModelInstallState> = emptyMap(),
    /** Android TTS engines installed on the phone. */
    val systemEngines: List<SystemEngineInfo> = emptyList(),
    /** Voices of each engine asked so far, by package; an empty list means the engine reported none. */
    val engineVoices: Map<String, List<SystemVoiceInfo>> = emptyMap(),
    val loadingEngines: Set<String> = emptySet(),
    /** The voice whose sample is playing now. */
    val previewVoice: VoiceChoice? = null,
    val russianDialogue: DialogueVoices = DialogueVoices(),
    val englishDialogue: DialogueVoices = DialogueVoices(),
    /** The sample dialogue is playing. */
    val dialoguePreviewing: Boolean = false,
) {
    val ruVoiceInstalled: Boolean get() = systemEngines.any { it.packageName == VoiceCatalog.RUVOICE_PACKAGE }
}

interface VoiceSettingsActions {
    fun selectVoice(language: String, voice: VoiceChoice)
    fun preview(language: String, voice: VoiceChoice)
    fun stopPreview()
    fun download(model: SpeechModel)
    fun cancelDownload(model: SpeechModel)
    fun delete(model: SpeechModel)
    fun setSpeed(speed: Float)
    fun setPauseScale(scale: Float)
    fun setSupertonicSteps(steps: Int)
    fun setPreferFullModels(enabled: Boolean)
    fun openRuVoicePage()
    fun openSystemTtsSettings()
    /** Opens the engine's own app, where RuVoice keeps its voices, stress dictionaries and packs. */
    fun openEngineApp(enginePackage: String)
    fun loadEngineVoices(enginePackage: String)
    fun refreshSystemVoices()
    fun setDialogue(language: String, dialogue: DialogueVoices)
    fun previewDialogue(language: String)
}
