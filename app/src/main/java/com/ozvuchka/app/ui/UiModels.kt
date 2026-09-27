package com.ozvuchka.app.ui

/** The small, storage-independent model shown in the library. */
data class LibraryBookUi(
    val id: String,
    val title: String,
    val author: String = "Неизвестный автор",
    val format: String = "EPUB",
    val progress: Float = 0f,
    val isAudioAvailable: Boolean = true,
)

enum class ReaderTheme {
    LIGHT,
    DARK,
    SEPIA,
}

/** All reader settings are supplied by the caller so they can be persisted between launches. */
data class ReaderUiState(
    val bookId: String,
    val title: String,
    val author: String = "",
    val chapterTitle: String,
    val chapterIndex: Int,
    val chapterCount: Int,
    val paragraphs: List<String>,
    val overallProgress: Float = 0f,
    val chapterProgress: Float = 0f,
    val isPlaying: Boolean = false,
    val speechSpeed: Float = 1f,
    val voiceId: Int = 0,
    val fontSizeSp: Float = 19f,
    val useSerif: Boolean = true,
    val theme: ReaderTheme = ReaderTheme.SEPIA,
    val isVoiceReady: Boolean = true,
    val isFullVoiceReady: Boolean = false,
    val preferFullVoice: Boolean = true,
    val voiceStatus: String? = null,
    val voiceDownloadProgress: Float? = null,
    val hasNextWebChapter: Boolean = false,
    val narrationParagraphIndex: Int? = null,
    val narrationTextOffset: Int = 0,
    val narrationTextLength: Int = 0,
)
