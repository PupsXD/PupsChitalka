package com.ozvuchka.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.WindowInsetsCompat
import com.ozvuchka.app.conversion.BookExporter
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.LibraryStore
import com.ozvuchka.app.importer.FileBookImporter
import com.ozvuchka.app.importer.WebChapter
import com.ozvuchka.app.importer.WebChapterImporter
import com.ozvuchka.app.speech.NeuralSpeechEngine
import com.ozvuchka.app.speech.NarrationController
import com.ozvuchka.app.speech.NarrationChapter
import com.ozvuchka.app.speech.SpeechPhase
import com.ozvuchka.app.speech.SpeechStatus
import com.ozvuchka.app.ui.LibraryBookUi
import com.ozvuchka.app.ui.LibraryScreen
import com.ozvuchka.app.ui.OzvuchkaTheme
import com.ozvuchka.app.ui.ReaderScreen
import com.ozvuchka.app.ui.ReaderTheme
import com.ozvuchka.app.ui.ReaderUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class MainActivity : ComponentActivity() {
    private lateinit var library: LibraryStore
    private lateinit var speech: NeuralSpeechEngine
    private val books = mutableStateListOf<Book>()
    private var currentBook by mutableStateOf<Book?>(null)
    private var importing by mutableStateOf(false)
    private var importProgressText by mutableStateOf("Подготавливаем книгу…")
    private var notice by mutableStateOf<String?>(null)
    private var speechStatus by mutableStateOf(SpeechStatus(SpeechPhase.MODEL_MISSING))
    private var isPlaying by mutableStateOf(false)
    private var narrationState by mutableStateOf(NarrationController.state.value)
    private var readerChromeVisible by mutableStateOf(true)
    private var pendingSave: Job? = null

    private val preferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private var fontSizeSp by mutableStateOf(19f)
    private var useSerif by mutableStateOf(true)
    private var speechSpeed by mutableStateOf(1f)
    private var voiceId by mutableStateOf(0)
    private var preferFullVoice by mutableStateOf(true)
    private var readerTheme by mutableStateOf(ReaderTheme.SEPIA)

    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFile(uri)
    }
    private val createEpub = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri -> if (uri != null) exportTo(uri, "epub") }
    private val createFb2 = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/x-fictionbook+xml")
    ) { uri -> if (uri != null) exportTo(uri, "fb2") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        library = LibraryStore(this)
        speech = NeuralSpeechEngine(this) { preferFullVoice }
        speechStatus = speech.status
        speech.onStatus = { status ->
            speechStatus = status
            if (status.phase == SpeechPhase.ERROR) notice = status.message
        }
        isPlaying = NarrationController.state.value.active &&
            NarrationController.state.value.speech.phase != SpeechPhase.PAUSED
        lifecycleScope.launch {
            NarrationController.state.collect { state ->
                narrationState = state
                isPlaying = state.active && state.speech.phase != SpeechPhase.PAUSED
                if (state.speech.phase == SpeechPhase.ERROR) notice = state.speech.message
                val visible = currentBook
                if (visible != null && state.active && state.bookId == visible.id &&
                    state.chapterIndex == visible.currentChapter && state.paragraphIndex != null &&
                    state.textLength > 0 && state.speech.phase in setOf(SpeechPhase.SPEAKING, SpeechPhase.PAUSED)
                ) {
                    val paragraphs = visible.chapters[visible.currentChapter].paragraphs
                    val paragraph = state.paragraphIndex.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0))
                    val length = paragraphs.getOrNull(paragraph)?.length?.coerceAtLeast(1) ?: 1
                    val progress = ((paragraph + state.textOffset.toFloat() / length) /
                        paragraphs.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                    if (kotlin.math.abs(progress - visible.chapterProgress) > 0.0001f) {
                        val updated = visible.copy(chapterProgress = progress)
                        currentBook = updated
                        replaceBook(updated)
                    }
                }
                if (visible != null && state.bookId == visible.id &&
                    (state.chapterIndex != visible.currentChapter || !state.active)
                ) {
                    pendingSave?.cancel()
                    val saved = withContext(Dispatchers.IO) { library.get(visible.id) }
                    if (saved != null) {
                        replaceBook(saved)
                        currentBook = saved
                    }
                }
            }
        }
        fontSizeSp = preferences.getFloat("fontSizeSp", 19f)
        useSerif = preferences.getBoolean("useSerif", true)
        speechSpeed = preferences.getFloat("speechSpeed", 1f)
        voiceId = preferences.getInt("voiceId", 0).coerceIn(0, 9)
        preferFullVoice = preferences.getBoolean("preferFullVoice", true)
        readerTheme = runCatching {
            ReaderTheme.valueOf(preferences.getString("theme", "SEPIA") ?: "SEPIA")
        }.getOrDefault(ReaderTheme.SEPIA)

        lifecycleScope.launch {
            books.addAll(withContext(Dispatchers.IO) { library.all() })
        }

        setContent {
            val snackbars = androidx.compose.runtime.remember { SnackbarHostState() }
            val systemBarsVisible = currentBook == null || readerChromeVisible
            LaunchedEffect(notice) {
                notice?.let { message ->
                    notice = null
                    snackbars.showSnackbar(message)
                }
            }
            OzvuchkaTheme(darkTheme = readerTheme == ReaderTheme.DARK) {
                SideEffect {
                    val dark = readerTheme == ReaderTheme.DARK
                    val barColor = when {
                        dark -> 0xFF171620.toInt()
                        currentBook != null && readerTheme == ReaderTheme.SEPIA -> 0xFFF5EFE3.toInt()
                        else -> 0xFFF8F7F5.toInt()
                    }
                    window.statusBarColor = barColor
                    window.navigationBarColor = barColor
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        if (systemBarsVisible) {
                            show(WindowInsetsCompat.Type.systemBars())
                        } else {
                            hide(WindowInsetsCompat.Type.systemBars())
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    val book = currentBook
                    if (book == null) {
                        LibraryScreen(
                            books = books.map { item ->
                                LibraryBookUi(
                                    id = item.id,
                                    title = item.title,
                                    author = item.author.ifBlank { "Неизвестный автор" },
                                    format = item.format.uppercase(),
                                    progress = ((item.currentChapter + item.chapterProgress) / item.chapters.size)
                                        .coerceIn(0f, 1f),
                                )
                            },
                            onOpenBook = { id -> currentBook = books.firstOrNull { it.id == id } },
                            onImportFile = { openDocument.launch(arrayOf("*/*")) },
                            onImportUrl = ::importUrl,
                            onDeleteBook = ::deleteBook,
                        )
                    } else {
                        BackHandler { closeReader() }
                        val chapterIndex = book.currentChapter.coerceIn(book.chapters.indices)
                        val chapter = book.chapters[chapterIndex]
                        val voiceStatusText = speechStatus.message ?: when (speechStatus.phase) {
                            SpeechPhase.DOWNLOADING -> "Загрузка голоса"
                            SpeechPhase.EXTRACTING -> "Установка голоса"
                            SpeechPhase.LOADING -> "Подготовка голоса"
                            SpeechPhase.SPEAKING -> "Воспроизведение"
                            SpeechPhase.PAUSED -> "Пауза"
                            SpeechPhase.ERROR -> "Ошибка озвучки"
                            SpeechPhase.MODEL_MISSING -> "Нейроголос ещё не загружен"
                            SpeechPhase.READY -> null
                        }
                        ReaderScreen(
                            state = ReaderUiState(
                                bookId = book.id,
                                title = book.title,
                                author = book.author,
                                chapterTitle = chapter.title,
                                chapterIndex = chapterIndex,
                                chapterCount = book.chapters.size,
                                paragraphs = chapter.paragraphs,
                                overallProgress = ((chapterIndex + book.chapterProgress) / book.chapters.size).coerceIn(0f, 1f),
                                chapterProgress = book.chapterProgress,
                                isPlaying = isPlaying,
                                speechSpeed = speechSpeed,
                                voiceId = voiceId,
                                fontSizeSp = fontSizeSp,
                                useSerif = useSerif,
                                theme = readerTheme,
                                isVoiceReady = speech.isModelInstalled(),
                                isFullVoiceReady = speech.isFullModelInstalled(),
                                preferFullVoice = preferFullVoice,
                                voiceStatus = voiceStatusText,
                                voiceDownloadProgress = if (
                                    speechStatus.phase == SpeechPhase.DOWNLOADING ||
                                    speechStatus.phase == SpeechPhase.EXTRACTING
                                ) {
                                    if (speechStatus.total > 0L) (
                                        speechStatus.completed.toFloat() / speechStatus.total
                                    ).coerceIn(0f, 1f) else 0f
                                } else null,
                                hasNextWebChapter = chapterIndex == book.chapters.lastIndex && chapter.nextUrl != null,
                                narrationParagraphIndex = if (
                                    narrationState.active && narrationState.bookId == book.id &&
                                    narrationState.chapterIndex == chapterIndex &&
                                    narrationState.textLength > 0 && narrationState.speech.phase in
                                    setOf(SpeechPhase.SPEAKING, SpeechPhase.PAUSED)
                                ) narrationState.paragraphIndex else null,
                                narrationTextOffset = narrationState.textOffset,
                                narrationTextLength = narrationState.textLength,
                            ),
                            onBack = ::closeReader,
                            onChapterChange = ::changeChapter,
                            onReadingProgressChange = ::changeReadingProgress,
                            onChromeVisibilityChange = { readerChromeVisible = it },
                            onPlayPause = ::toggleSpeech,
                            onSpeechSpeedChange = { speed ->
                                speechSpeed = speed
                                preferences.edit().putFloat("speechSpeed", speed).apply()
                            },
                            onVoiceChange = { id ->
                                voiceId = id.coerceIn(0, 9)
                                preferences.edit().putInt("voiceId", voiceId).apply()
                            },
                            onPreviewVoice = {
                                NarrationController.stop(this@MainActivity)
                                speech.speak(
                                    "Это пример голоса для чтения вашей книги. Проверьте, нравится ли вам его тембр и интонация.",
                                    language = "ru",
                                    voiceId = voiceId,
                                    speed = speechSpeed,
                                )
                            },
                            onFontSizeChange = { size ->
                                fontSizeSp = size
                                preferences.edit().putFloat("fontSizeSp", size).apply()
                            },
                            onSerifChange = { serif ->
                                useSerif = serif
                                preferences.edit().putBoolean("useSerif", serif).apply()
                            },
                            onThemeChange = { theme ->
                                readerTheme = theme
                                preferences.edit().putString("theme", theme.name).apply()
                            },
                            onDownloadVoice = { speech.installModel() },
                            onDownloadFullVoice = { speech.installFullModel() },
                            onCancelVoiceDownload = { speech.cancelInstall() },
                            onQualityChange = { full ->
                                preferFullVoice = full
                                preferences.edit().putBoolean("preferFullVoice", full).apply()
                            },
                            onExport = ::requestExport,
                            onImportNextChapter = ::importNextChapter,
                        )
                    }
                    if (importing) {
                        androidx.compose.material3.Surface(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.surface,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                            shadowElevation = 12.dp,
                        ) {
                            Column(
                                modifier = Modifier.padding(28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CircularProgressIndicator()
                                Text(importProgressText, modifier = Modifier.padding(top = 18.dp))
                            }
                        }
                    }
                    SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        receiveIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIncomingIntent(intent)
    }

    override fun onDestroy() {
        pendingSave?.cancel()
        currentBook?.let { book -> library.save(book) }
        speech.close()
        super.onDestroy()
    }

    private fun receiveIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") importUrl(uri.toString())
                else importFile(uri)
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val sharedFile = intent.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
                if (sharedFile != null) {
                    importFile(sharedFile)
                    return
                }
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                val url = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
                    .find(shared)?.value?.trimEnd('.', ',', ')', ']') ?: return
                importUrl(url)
            }
        }
    }

    private fun importFile(uri: android.net.Uri) {
        if (importing) return
        importing = true
        importProgressText = "Читаем файл…"
        lifecycleScope.launch {
            try {
                val book = FileBookImporter.importBook(this@MainActivity, uri) { message ->
                    runOnUiThread { importProgressText = message }
                }
                withContext(Dispatchers.IO) { library.save(book) }
                books.add(0, book)
                currentBook = book
                if (book.warnings.isNotEmpty()) notice = book.warnings.take(2).joinToString(" ")
            } catch (error: Exception) {
                notice = "Не удалось открыть файл: ${error.message ?: "неизвестная ошибка"}"
            } finally {
                importing = false
                importProgressText = "Подготавливаем книгу…"
            }
        }
    }

    private fun importUrl(url: String) {
        if (importing) return
        importing = true
        importProgressText = "Загружаем главу…"
        lifecycleScope.launch {
            try {
                val chapter = WebChapterImporter.importChapter(url)
                val existing = books.firstOrNull { book ->
                    book.chapters.any { it.sourceUrl == chapter.sourceUrl }
                }
                if (existing != null) {
                    currentBook = existing
                } else {
                    val titleParts = chapter.title.split(" | ", limit = 2)
                    val bookTitle = titleParts.getOrNull(1)?.trim().orEmpty().ifBlank { chapter.title }
                    val book = Book(
                        title = bookTitle,
                        format = "web",
                        source = chapter.sourceUrl,
                        chapters = listOf(chapter.toBookChapter()),
                    )
                    withContext(Dispatchers.IO) { library.save(book) }
                    books.add(0, book)
                    currentBook = book
                }
            } catch (error: Exception) {
                notice = "Не удалось добавить главу: ${error.message ?: "неизвестная ошибка"}"
            } finally {
                importing = false
                importProgressText = "Подготавливаем книгу…"
            }
        }
    }

    private fun importNextChapter() {
        val book = currentBook ?: return
        val nextUrl = book.chapters.lastOrNull()?.nextUrl ?: return
        if (importing) return
        importing = true
        importProgressText = "Загружаем следующую главу…"
        lifecycleScope.launch {
            try {
                val next = WebChapterImporter.importChapter(nextUrl)
                if (book.chapters.any { it.sourceUrl == next.sourceUrl }) {
                    notice = "Эта глава уже есть в книге"
                } else {
                    val updated = book.copy(
                        chapters = book.chapters + next.toBookChapter(),
                        currentChapter = book.chapters.size,
                        chapterProgress = 0f,
                    )
                    withContext(Dispatchers.IO) { library.save(updated) }
                    replaceBook(updated)
                    currentBook = updated
                }
            } catch (error: Exception) {
                notice = "Не удалось загрузить следующую главу: ${error.message ?: "ошибка сети"}"
            } finally {
                importing = false
                importProgressText = "Подготавливаем книгу…"
            }
        }
    }

    private fun WebChapter.toBookChapter(): Chapter {
        val body = Jsoup.parseBodyFragment(html).body()
        val selected = body.select("p, h2, h3, blockquote, li")
            .map { it.text().trim() }.filter(String::isNotBlank)
        val paragraphs = if (selected.isNotEmpty()) selected else body.wholeText().split(Regex("\\n+"))
            .map(String::trim).filter(String::isNotBlank)
        val chapterTitle = title.substringBefore(" | ").trim().ifBlank { title }
        return Chapter(chapterTitle, paragraphs, sourceUrl, nextUrl)
    }

    private fun changeChapter(index: Int) {
        val book = currentBook ?: return
        if (index !in book.chapters.indices) return
        NarrationController.stop(this)
        isPlaying = false
        val updated = book.copy(currentChapter = index, chapterProgress = 0f)
        currentBook = updated
        replaceBook(updated)
        pendingSave?.cancel()
        library.save(updated)
    }

    private fun changeReadingProgress(overall: Float) {
        val book = currentBook ?: return
        if (narrationState.active && narrationState.bookId == book.id &&
            narrationState.chapterIndex == book.currentChapter
        ) return
        val within = (overall * book.chapters.size - book.currentChapter).coerceIn(0f, 1f)
        if (kotlin.math.abs(within - book.chapterProgress) < 0.0001f) return
        val updated = book.copy(chapterProgress = within)
        currentBook = updated
        replaceBook(updated)
        saveSoon(updated)
    }

    private fun saveSoon(book: Book, immediate: Boolean = false) {
        pendingSave?.cancel()
        pendingSave = lifecycleScope.launch {
            if (!immediate) delay(900)
            withContext(Dispatchers.IO) { library.save(book) }
        }
    }

    private fun replaceBook(book: Book) {
        val index = books.indexOfFirst { it.id == book.id }
        if (index >= 0) books[index] = book
    }

    private fun closeReader() {
        pendingSave?.cancel()
        currentBook?.let { library.save(it) }
        currentBook = null
    }

    private fun toggleSpeech() {
        val narration = NarrationController.state.value
        if (narration.active) {
            if (narration.speech.phase == SpeechPhase.PAUSED) NarrationController.resume(this)
            else NarrationController.pause(this)
            return
        }
        val book = currentBook ?: return
        if (!speech.isModelInstalled()) {
            notice = "Загрузите нейроголос в настройках читалки"
            return
        }
        pendingSave?.cancel()
        val startIndex = book.currentChapter.coerceIn(book.chapters.indices)
        val narrationChapters = book.chapters.drop(startIndex).mapIndexed { offset, chapter ->
            val location = if (offset == 0) {
                book.chapterProgress * chapter.paragraphs.size
            } else 0f
            val paragraphIndex = location.toInt().coerceIn(0, chapter.paragraphs.size)
            val textOffset = chapter.paragraphs.getOrNull(paragraphIndex)?.let { paragraph ->
                ((location - paragraphIndex) * paragraph.length).toInt().coerceIn(0, paragraph.length)
            } ?: 0
            val paragraphs = chapter.paragraphs.drop(paragraphIndex).toMutableList()
            if (paragraphs.isNotEmpty()) paragraphs[0] = paragraphs[0].drop(textOffset)
            val text = paragraphs.joinToString("\n\n")
            NarrationChapter(
                title = "${book.title} — ${chapter.title}",
                text = text,
                language = if (text.any { it in '\u0400'..'\u04ff' }) "ru" else "en",
                bookId = book.id,
                chapterIndex = startIndex + offset,
                paragraphs = paragraphs,
                startParagraphIndex = paragraphIndex,
                startTextOffset = textOffset,
                totalParagraphs = chapter.paragraphs.size,
            )
        }
        if (narrationChapters.all { it.text.isBlank() }) {
            notice = "В этой главе нет текста для озвучки"
            return
        }
        NarrationController.playChapters(
            context = this,
            chapters = narrationChapters,
            speed = speechSpeed,
            voiceId = voiceId,
        )
    }

    private fun deleteBook(id: String) {
        lifecycleScope.launch {
            try {
                val removed = withContext(Dispatchers.IO) { library.delete(id) }
                if (removed) {
                    books.removeAll { it.id == id }
                    notice = "Книга удалена из библиотеки"
                } else {
                    notice = "Книга уже удалена"
                }
            } catch (error: Exception) {
                notice = "Не удалось удалить книгу: ${error.message ?: "ошибка записи"}"
            }
        }
    }

    private fun requestExport(format: String) {
        val book = currentBook ?: return
        val safeTitle = book.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60).ifBlank { "Книга" }
        when (format) {
            "epub" -> createEpub.launch("$safeTitle.epub")
            "fb2" -> createFb2.launch("$safeTitle.fb2")
        }
    }

    private fun exportTo(uri: android.net.Uri, format: String) {
        val book = currentBook ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { BookExporter.write(book, format, it) }
                        ?: error("Не удалось создать файл")
                }
                notice = "Книга сохранена в формате ${format.uppercase()}"
            } catch (error: Exception) {
                notice = "Не удалось сохранить книгу: ${error.message ?: "ошибка записи"}"
            }
        }
    }
}
