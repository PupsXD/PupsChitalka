package com.ozvuchka.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.ozvuchka.app.conversion.BookExporter
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.LibraryStore
import com.ozvuchka.app.data.chapterProgressOf
import com.ozvuchka.app.importer.FileBookImporter
import com.ozvuchka.app.importer.WebChapter
import com.ozvuchka.app.importer.WebChapterImporter
import com.ozvuchka.app.speech.ModelInstallState
import com.ozvuchka.app.speech.NarrationController
import com.ozvuchka.app.speech.NarrationPhase
import com.ozvuchka.app.speech.NarrationState
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.SpeechModels
import com.ozvuchka.app.speech.SpeechSettings
import com.ozvuchka.app.speech.SynthesisHub
import com.ozvuchka.app.speech.SystemEngineInfo
import com.ozvuchka.app.speech.SystemVoiceInfo
import com.ozvuchka.app.speech.SystemVoices
import com.ozvuchka.app.speech.VoiceCatalog
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import com.ozvuchka.app.speech.dominantLanguage
import com.ozvuchka.app.ui.LibraryBookUi
import com.ozvuchka.app.ui.LibraryScreen
import com.ozvuchka.app.ui.OzvuchkaTheme
import com.ozvuchka.app.ui.ReaderActions
import com.ozvuchka.app.ui.ReaderMargin
import com.ozvuchka.app.ui.ReaderNarrationUi
import com.ozvuchka.app.ui.ReaderScreen
import com.ozvuchka.app.ui.ReaderTheme
import com.ozvuchka.app.ui.ReaderTypography
import com.ozvuchka.app.ui.ReaderUiState
import com.ozvuchka.app.ui.VoiceSettingsActions
import com.ozvuchka.app.ui.VoiceSettingsSheet
import com.ozvuchka.app.ui.VoiceSettingsUi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

class MainActivity : ComponentActivity() {
    private lateinit var library: LibraryStore
    private val books = mutableStateListOf<Book>()
    private var currentBook by mutableStateOf<Book?>(null)
    private var importing by mutableStateOf(false)
    private var importProgressText by mutableStateOf("Подготавливаем книгу…")
    private var notice by mutableStateOf<String?>(null)
    private var narration by mutableStateOf(NarrationController.state.value)

    private val preferences by lazy { getSharedPreferences("reader", MODE_PRIVATE) }
    private var typography by mutableStateOf(ReaderTypography())
    private var readerTheme by mutableStateOf(ReaderTheme.SEPIA)
    private var volumeKeysTurnPages by mutableStateOf(true)
    private var keepScreenOn by mutableStateOf(true)
    private var highlightWords by mutableStateOf(true)
    private var speech by mutableStateOf<SpeechSettings?>(null)
    private var modelStates by mutableStateOf<Map<SpeechModel, ModelInstallState>>(emptyMap())
    private var installedModels by mutableStateOf<Set<SpeechModel>>(emptySet())
    private var systemEngines by mutableStateOf<List<SystemEngineInfo>>(emptyList())
    private val installedEngines: Set<String> get() = systemEngines.mapTo(HashSet()) { it.packageName }
    /** Voices each Android TTS engine reported, loaded one engine at a time when needed. */
    private var engineVoices by mutableStateOf<Map<String, List<SystemVoiceInfo>>>(emptyMap())
    private var loadingEngines by mutableStateOf<Set<String>>(emptySet())
    private var showVoices by mutableStateOf(false)
    private var voicesLanguage by mutableStateOf("ru")
    private var previewVoice by mutableStateOf<VoiceChoice?>(null)
    private val pageTurns = MutableSharedFlow<Int>(extraBufferCapacity = 4)

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
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        library = LibraryStore(this)
        loadPreferences()
        speech = SpeechSettings.load(this)
        refreshInstalledModels()

        lifecycleScope.launch {
            NarrationController.state.collect(::onNarrationState)
        }
        lifecycleScope.launch {
            SpeechModels.states.collect { states ->
                val finished = states.filter { (model, state) ->
                    state.stage == ModelInstallState.Stage.DONE && modelStates[model]?.stage != ModelInstallState.Stage.DONE
                }
                states.values.firstOrNull { state ->
                    state.stage == ModelInstallState.Stage.FAILED && modelStates[state.model]?.stage != ModelInstallState.Stage.FAILED
                }?.let { notice = it.message }
                modelStates = states
                if (finished.isNotEmpty()) {
                    refreshInstalledModels()
                    finished.keys.forEach(::onModelInstalled)
                }
            }
        }
        lifecycleScope.launch {
            books.addAll(withContext(Dispatchers.IO) { library.all() })
        }

        setContent {
            val snackbars = remember { SnackbarHostState() }
            val systemDark = isSystemInDarkTheme()
            val book = currentBook
            val dark = if (book != null) readerTheme.isDark else systemDark || readerTheme.isDark
            LaunchedEffect(notice) {
                notice?.let { message ->
                    notice = null
                    snackbars.showSnackbar(message)
                }
            }
            OzvuchkaTheme(darkTheme = dark) {
                SideEffect {
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                        systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        // In a book the reader shows and hides the bars itself, see chromeVisibilityChanged.
                        if (book == null) show(WindowInsetsCompat.Type.systemBars())
                    }
                    if (book != null && keepScreenOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (book == null) {
                        LibraryScreen(
                            books = books.map(::libraryItem),
                            narratingBookId = narration.bookId.takeIf { narration.active && !narration.isPreview },
                            narrationPlaying = narration.isPlaying,
                            onOpenBook = ::openBook,
                            onListen = ::listenFromLibrary,
                            onImportFile = { openDocument.launch(arrayOf("*/*")) },
                            onImportUrl = ::importUrl,
                            onDeleteBook = ::deleteBook,
                            onOpenVoices = { openVoices("ru") },
                        )
                    } else {
                        BackHandler { closeReader() }
                        ReaderScreen(
                            state = readerState(book),
                            actions = readerActions,
                            pageTurns = pageTurns,
                        )
                    }
                    if (showVoices) {
                        speech?.let { settings ->
                            VoiceSettingsSheet(
                                state = VoiceSettingsUi(
                                    russianVoice = settings.russianVoice,
                                    englishVoice = settings.englishVoice,
                                    speed = settings.speed,
                                    pauseScale = settings.pauseScale,
                                    supertonicSteps = settings.supertonicSteps,
                                    preferFullModels = settings.preferFullModels,
                                    installedModels = installedModels,
                                    installStates = modelStates,
                                    russianVoiceLabel = voiceLabel(settings.russianVoice),
                                    englishVoiceLabel = voiceLabel(settings.englishVoice),
                                    systemEngines = systemEngines,
                                    engineVoices = engineVoices,
                                    loadingEngines = loadingEngines,
                                    previewVoice = previewVoice.takeIf { narration.isPreview && narration.active },
                                ),
                                initialLanguage = voicesLanguage,
                                actions = voiceActions,
                                onDismiss = { showVoices = false },
                            )
                        }
                    }
                    if (importing) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center),
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(20.dp),
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
                    SnackbarHost(snackbars, modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                }
            }
        }
        receiveIncomingIntent(intent)
    }

    /** Last chrome state the reader asked for; re-applied on resume, when some launchers restore the bars. */
    private var readerChromeShown = true

    override fun onResume() {
        super.onResume()
        refreshEngines()
        if (currentBook != null && !readerChromeShown) readerActions.chromeVisibilityChanged(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveIncomingIntent(intent)
    }

    override fun onPause() {
        currentBook?.let { saveReadingPosition(it) }
        super.onPause()
    }

    /** Volume keys turn pages while reading silently; during narration they keep adjusting volume. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val direction = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> 1
            KeyEvent.KEYCODE_VOLUME_UP -> -1
            else -> 0
        }
        val narratingHere = narration.active && narration.bookId == currentBook?.id
        if (direction != 0 && currentBook != null && volumeKeysTurnPages && !narratingHere && !showVoices) {
            if (event.action == KeyEvent.ACTION_DOWN) pageTurns.tryEmit(direction)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- state

    private fun loadPreferences() {
        typography = ReaderTypography(
            fontSizeSp = preferences.getFloat("fontSizeSp", 19f),
            useSerif = preferences.getBoolean("useSerif", true),
            lineSpacing = preferences.getFloat("lineSpacing", 1.55f),
            justify = preferences.getBoolean("justify", true),
            paragraphIndent = preferences.getBoolean("paragraphIndent", true),
            margin = runCatching { ReaderMargin.valueOf(preferences.getString("margin", null) ?: "NORMAL") }
                .getOrDefault(ReaderMargin.NORMAL),
        )
        readerTheme = runCatching {
            ReaderTheme.valueOf(preferences.getString("theme", "SEPIA") ?: "SEPIA")
        }.getOrDefault(ReaderTheme.SEPIA)
        volumeKeysTurnPages = preferences.getBoolean("volumeKeysTurnPages", true)
        keepScreenOn = preferences.getBoolean("keepScreenOn", true)
        highlightWords = preferences.getBoolean("highlightWords", true)
    }

    private fun saveTypography(value: ReaderTypography) {
        typography = value
        preferences.edit()
            .putFloat("fontSizeSp", value.fontSizeSp)
            .putBoolean("useSerif", value.useSerif)
            .putFloat("lineSpacing", value.lineSpacing)
            .putBoolean("justify", value.justify)
            .putBoolean("paragraphIndent", value.paragraphIndent)
            .putString("margin", value.margin.name)
            .apply()
    }

    private fun updateSpeech(change: (SpeechSettings) -> SpeechSettings) {
        val current = speech ?: SpeechSettings.load(this)
        val next = change(current)
        speech = next
        SpeechSettings.save(this, next)
        NarrationController.settingsChanged(this)
    }

    private fun refreshInstalledModels() {
        installedModels = SpeechModel.entries.filter { SpeechModels.isInstalled(this, it) }.toSet()
    }

    private fun refreshEngines() {
        lifecycleScope.launch {
            val engines = withContext(Dispatchers.IO) { SystemVoices.engines(this@MainActivity) }
            val found = engines.mapTo(HashSet()) { it.packageName }
            val ruVoiceAppeared = VoiceCatalog.RUVOICE_PACKAGE in found && VoiceCatalog.RUVOICE_PACKAGE !in installedEngines
            systemEngines = engines
            engineVoices = engineVoices.filterKeys { it in found }
            // The first time RuVoice is found, Russian books switch to Silero unless a voice was chosen by hand.
            if (ruVoiceAppeared && !preferences.getBoolean("voiceRuChosen", false)) {
                updateSpeech { it.copy(russianVoice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE)) }
                notice = "Найден RuVoice: русский текст читает Silero v5"
            }
            // Back from an engine's settings the voice lists may have changed (a new RuVoice pack).
            if (showVoices) loadSelectedEngineVoices(reload = true)
        }
    }

    private fun onModelInstalled(model: SpeechModel) {
        val settings = speech ?: return
        when (model) {
            SpeechModel.KOKORO, SpeechModel.KOKORO_FULL -> if (settings.englishVoice.engine == VoiceEngine.SUPERTONIC) {
                updateSpeech { it.copy(englishVoice = VoiceChoice(VoiceEngine.KOKORO, 3)) }
            }
            else -> Unit
        }
        notice = "${model.title}: голос установлен"
    }

    private fun onNarrationState(state: NarrationState) {
        val previous = narration
        narration = state
        if (!state.isPreview || !state.active) {
            if (!state.active) previewVoice = null
        }
        if (state.message != null && state.messageId != previous.messageId) notice = state.message
        val visible = currentBook ?: return
        if (state.isPreview || state.bookId != visible.id || state.chapterIndex == null) return
        if (!state.active) {
            // The service saved the final position; pick it up so the reader opens there next time.
            if (previous.active && previous.bookId == visible.id) {
                lifecycleScope.launch {
                    val saved = withContext(Dispatchers.IO) { library.get(visible.id) } ?: return@launch
                    if (currentBook?.id == saved.id) {
                        currentBook = currentBook?.copy(currentChapter = saved.currentChapter, chapterProgress = saved.chapterProgress)
                        currentBook?.let(::replaceBook)
                    }
                }
            }
            return
        }
        val chapter = visible.chapters.getOrNull(state.chapterIndex) ?: return
        val paragraph = state.paragraphIndex ?: return
        val progress = if (paragraph < 0) 0f else chapterProgressOf(chapter.paragraphs, paragraph, state.textOffset)
        if (state.chapterIndex != visible.currentChapter || kotlin.math.abs(progress - visible.chapterProgress) > 0.0001f) {
            val updated = visible.copy(currentChapter = state.chapterIndex, chapterProgress = progress)
            currentBook = updated
            replaceBook(updated)
        }
    }

    private fun libraryItem(book: Book) = LibraryBookUi(
        id = book.id,
        title = book.title,
        author = book.author.ifBlank { "Неизвестный автор" },
        format = book.format.uppercase(),
        progress = book.overallProgress,
        chapterTitle = book.chapters.getOrNull(book.currentChapter)?.title.orEmpty(),
        lastOpenedAt = book.lastOpenedAt,
    )

    private fun readerState(book: Book): ReaderUiState {
        val chapterIndex = book.currentChapter.coerceIn(book.chapters.indices)
        val chapter = book.chapters[chapterIndex]
        val language = dominantLanguage(chapter.paragraphs)
        val settings = speech
        val sameBook = narration.active && !narration.isPreview && narration.bookId == book.id
        val voice = settings?.voiceFor(language)
        return ReaderUiState(
            bookId = book.id,
            title = book.title,
            author = book.author,
            chapterTitle = chapter.title,
            chapterIndex = chapterIndex,
            chapterCount = book.chapters.size,
            chapterTitles = book.chapters.map { it.title },
            paragraphs = chapter.paragraphs,
            language = language,
            overallProgress = book.overallProgress,
            chapterProgress = book.chapterProgress,
            typography = typography,
            theme = readerTheme,
            narration = ReaderNarrationUi(
                active = sameBook,
                playing = sameBook && narration.isPlaying,
                preparing = sameBook && narration.phase == NarrationPhase.PREPARING,
                paragraphIndex = if (sameBook && narration.chapterIndex == chapterIndex) narration.paragraphIndex else null,
                textOffset = narration.textOffset,
                textLength = narration.textLength,
                wordOffset = if (highlightWords) narration.wordOffset else -1,
                wordLength = narration.wordLength,
                speed = settings?.speed ?: 1f,
                sleepEndsAt = narration.sleepEndsAt.takeIf { sameBook },
                sleepAtChapterEnd = sameBook && narration.sleepAtChapterEnd,
                voiceReady = voice != null && (isUsable(voice) || isUsable(VoiceChoice(VoiceEngine.SUPERTONIC))),
                voiceLabel = voice?.let(::voiceLabel).orEmpty(),
            ),
            hasNextWebChapter = chapterIndex == book.chapters.lastIndex && chapter.nextUrl != null,
            volumeKeysTurnPages = volumeKeysTurnPages,
            keepScreenOn = keepScreenOn,
            highlightWords = highlightWords,
        )
    }

    private fun isUsable(voice: VoiceChoice): Boolean = when (voice.engine) {
        VoiceEngine.SUPERTONIC -> SpeechModel.SUPERTONIC in installedModels || SpeechModel.SUPERTONIC_FULL in installedModels
        VoiceEngine.KOKORO -> SpeechModel.KOKORO in installedModels || SpeechModel.KOKORO_FULL in installedModels
        VoiceEngine.SYSTEM -> voice.enginePackage in installedEngines
    }

    private fun voiceLabel(voice: VoiceChoice): String = VoiceCatalog.presetTitle(voice) ?: run {
        val engine = systemEngines.firstOrNull { it.packageName == voice.enginePackage }
        val known = engineVoices[voice.enginePackage]?.firstOrNull { it.name == voice.voiceName }
        val name = when {
            voice.voiceName.isBlank() -> "голос по умолчанию"
            known != null -> SystemVoices.describe(known)
            voice.enginePackage == VoiceCatalog.RUVOICE_PACKAGE -> voice.voiceName.substringBefore('-').replaceFirstChar { it.uppercase() }
            else -> voice.voiceName
        }
        "${SystemVoices.engineTitle(voice.enginePackage, engine?.label)} · $name"
    }

    // ---------------------------------------------------------------- reader actions

    private val readerActions = object : ReaderActions {
        override fun back() = closeReader()

        override fun changeChapter(index: Int) {
            val book = currentBook ?: return
            if (index !in book.chapters.indices) return
            val updated = book.copy(currentChapter = index, chapterProgress = 0f)
            currentBook = updated
            replaceBook(updated)
            library.updatePosition(book.id, index, 0f)
            // Listening continues from the chapter the reader jumped to.
            if (narration.active && narration.bookId == book.id && !narration.isPreview) {
                NarrationController.playBook(this@MainActivity, updated, index, 0, 0)
            }
        }

        override fun readingProgressChanged(overall: Float) {
            val book = currentBook ?: return
            if (narration.active && narration.bookId == book.id && narration.chapterIndex == book.currentChapter) return
            val within = (overall * book.chapters.size - book.currentChapter).coerceIn(0f, 1f)
            if (kotlin.math.abs(within - book.chapterProgress) < 0.0001f) return
            val updated = book.copy(chapterProgress = within)
            currentBook = updated
            replaceBook(updated)
            library.updatePosition(book.id, book.currentChapter, within)
        }

        // Straight to the window, not through Compose state: a state change here would recompose
        // the whole screen at the start of the chrome animation and make it stutter.
        override fun chromeVisibilityChanged(visible: Boolean) {
            readerChromeShown = visible
            WindowInsetsControllerCompat(window, window.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                if (visible) show(WindowInsetsCompat.Type.systemBars()) else hide(WindowInsetsCompat.Type.systemBars())
            }
        }

        override fun playPause() {
            val book = currentBook ?: return
            if (narration.active && !narration.isPreview && narration.bookId == book.id) {
                NarrationController.toggle(this@MainActivity)
                return
            }
            val (paragraph, offset) = book.position()
            startNarration(book, book.currentChapter, paragraph, offset)
        }

        override fun readFrom(paragraph: Int, offset: Int) {
            val book = currentBook ?: return
            startNarration(book, book.currentChapter, paragraph, offset)
        }

        override fun nextSentence() = NarrationController.next(this@MainActivity)
        override fun previousSentence() = NarrationController.previous(this@MainActivity)
        override fun stopNarration() = NarrationController.stop(this@MainActivity)
        override fun setSpeed(speed: Float) = updateSpeech { it.copy(speed = speed) }
        override fun setSleepTimer(minutes: Int) = NarrationController.setSleepTimer(this@MainActivity, minutes)

        override fun openVoices() {
            val book = currentBook
            val language = book?.chapters?.getOrNull(book.currentChapter)?.paragraphs?.let { dominantLanguage(it) } ?: "ru"
            openVoices(language)
        }

        override fun typographyChanged(typography: ReaderTypography) = saveTypography(typography)

        override fun themeChanged(theme: ReaderTheme) {
            readerTheme = theme
            preferences.edit().putString("theme", theme.name).apply()
        }

        override fun volumeKeysChanged(enabled: Boolean) {
            volumeKeysTurnPages = enabled
            preferences.edit().putBoolean("volumeKeysTurnPages", enabled).apply()
        }

        override fun highlightWordsChanged(enabled: Boolean) {
            highlightWords = enabled
            preferences.edit().putBoolean("highlightWords", enabled).apply()
        }

        override fun keepScreenOnChanged(enabled: Boolean) {
            keepScreenOn = enabled
            preferences.edit().putBoolean("keepScreenOn", enabled).apply()
        }

        override fun export(format: String) = requestExport(format)
        override fun importNextChapter() = this@MainActivity.importNextChapter()
    }

    private fun startNarration(book: Book, chapter: Int, paragraph: Int, offset: Int) {
        val settings = speech ?: SpeechSettings.load(this)
        val language = dominantLanguage(book.chapters.getOrNull(chapter)?.paragraphs.orEmpty())
        if (!isUsable(settings.voiceFor(language)) && !isUsable(VoiceChoice(VoiceEngine.SUPERTONIC))) {
            notice = "Сначала выберите и скачайте голос"
            openVoices(language)
            return
        }
        NarrationController.playBook(this, book, chapter, paragraph, offset)
    }

    private fun openVoices(language: String) {
        voicesLanguage = language
        showVoices = true
        loadSelectedEngineVoices(reload = true)
    }

    // ---------------------------------------------------------------- voice actions

    private val voiceActions = object : VoiceSettingsActions {
        override fun selectVoice(language: String, voice: VoiceChoice) {
            if (language != "en") preferences.edit().putBoolean("voiceRuChosen", true).apply()
            updateSpeech { if (language == "en") it.copy(englishVoice = voice) else it.copy(russianVoice = voice) }
            speech?.let { settings -> if (isUsable(voice)) SynthesisHub.shared(this@MainActivity).warmUpAsync(voice, settings) }
        }

        override fun preview(language: String, voice: VoiceChoice) {
            previewVoice = voice
            val sample = if (language == "en") {
                "The old house stood at the end of the lane, and every window was dark. Are you coming back? she whispered."
            } else {
                "Он остановился у окна. За стеклом медленно падал снег. — Ты вернёшься? — спросила она почти шёпотом."
            }
            NarrationController.preview(this@MainActivity, voice, language, sample)
        }

        override fun stopPreview() {
            if (narration.isPreview && narration.active) NarrationController.stop(this@MainActivity)
            previewVoice = null
        }

        override fun download(model: SpeechModel) = SpeechModels.install(this@MainActivity, model)
        override fun cancelDownload(model: SpeechModel) = SpeechModels.cancel(model)

        override fun delete(model: SpeechModel) {
            if (narration.active) NarrationController.stop(this@MainActivity)
            lifecycleScope.launch {
                withContext(Dispatchers.IO) { SpeechModels.delete(this@MainActivity, model) }
                refreshInstalledModels()
                notice = "${model.title}: модель удалена"
            }
        }

        override fun setSpeed(speed: Float) = updateSpeech { it.copy(speed = speed) }
        override fun setPauseScale(scale: Float) = updateSpeech { it.copy(pauseScale = scale) }
        override fun setSupertonicSteps(steps: Int) = updateSpeech { it.copy(supertonicSteps = steps) }
        override fun setPreferFullModels(enabled: Boolean) = updateSpeech { it.copy(preferFullModels = enabled) }

        override fun openRuVoicePage() = openUrl(VoiceCatalog.RUVOICE_RELEASES)

        override fun openSystemTtsSettings() {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: ActivityNotFoundException) {
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }
        }

        override fun openEngineApp(enginePackage: String) {
            val launch = packageManager.getLaunchIntentForPackage(enginePackage)
            if (launch != null) startActivity(launch) else openSystemTtsSettings()
        }

        override fun loadEngineVoices(enginePackage: String) = this@MainActivity.loadEngineVoices(enginePackage)

        override fun refreshSystemVoices() {
            loadSelectedEngineVoices(reload = true)
            refreshEngines()
        }
    }

    /** Voices of the engines chosen now, plus RuVoice, so the picker opens with its list ready. */
    private fun loadSelectedEngineVoices(reload: Boolean = false) {
        val settings = speech ?: return
        listOf(settings.russianVoice, settings.englishVoice)
            .filter { it.engine == VoiceEngine.SYSTEM }
            .map { it.enginePackage }
            .plus(VoiceCatalog.RUVOICE_PACKAGE)
            .distinct()
            .forEach { loadEngineVoices(it, reload) }
    }

    /**
     * Asks one engine for its voices. Each engine is bound separately and its list shows up as soon
     * as it is ready, so a slow engine does not hide the others; a reload keeps the old list on
     * screen until the new one arrives.
     */
    private fun loadEngineVoices(enginePackage: String, reload: Boolean = false) {
        if (enginePackage in loadingEngines || (!reload && enginePackage in engineVoices)) return
        val engine = systemEngines.firstOrNull { it.packageName == enginePackage } ?: return
        loadingEngines = loadingEngines + enginePackage
        lifecycleScope.launch {
            val voices = withContext(Dispatchers.IO) {
                runCatching { SystemVoices.voices(this@MainActivity, engine) }.getOrDefault(emptyList())
            }
            engineVoices = engineVoices + (enginePackage to voices)
            loadingEngines = loadingEngines - enginePackage
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            notice = "Не найден браузер для ссылки $url"
        }
    }

    // ---------------------------------------------------------------- library

    private fun openBook(id: String) {
        val book = books.firstOrNull { it.id == id } ?: return
        val opened = book.copy(lastOpenedAt = System.currentTimeMillis())
        currentBook = opened
        replaceBook(opened)
        library.updatePosition(opened.id, opened.currentChapter, opened.chapterProgress, opened.lastOpenedAt)
        warmUpVoice(opened)
    }

    /** Loads the book's voice in the background, so «Слушать» starts without a model load. */
    private fun warmUpVoice(book: Book) {
        val settings = speech ?: return
        val language = dominantLanguage(book.chapters.getOrNull(book.currentChapter)?.paragraphs.orEmpty())
        val voice = settings.voiceFor(language)
        if (isUsable(voice)) SynthesisHub.shared(this).warmUpAsync(voice, settings)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_BACKGROUND) SynthesisHub.shared(this).releaseIfIdle()
    }

    private fun listenFromLibrary(id: String) {
        if (narration.active && narration.bookId == id && !narration.isPreview) {
            NarrationController.toggle(this)
            return
        }
        openBook(id)
        val book = currentBook ?: return
        val (paragraph, offset) = book.position()
        startNarration(book, book.currentChapter, paragraph, offset)
    }

    private fun receiveIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.data?.let { uri ->
                if (uri.scheme == "http" || uri.scheme == "https") importUrl(uri.toString())
                else importFile(uri)
            }
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val sharedFile = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
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

    private fun importFile(uri: Uri) {
        if (importing) return
        importing = true
        importProgressText = "Читаем файл…"
        lifecycleScope.launch {
            try {
                val book = FileBookImporter.importBook(this@MainActivity, uri) { message ->
                    runOnUiThread { importProgressText = message }
                }.copy(lastOpenedAt = System.currentTimeMillis())
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
                    openBook(existing.id)
                } else {
                    val titleParts = chapter.title.split(" | ", limit = 2)
                    val bookTitle = titleParts.getOrNull(1)?.trim().orEmpty().ifBlank { chapter.title }
                    val book = Book(
                        title = bookTitle,
                        format = "web",
                        source = chapter.sourceUrl,
                        chapters = listOf(chapter.toBookChapter()),
                        lastOpenedAt = System.currentTimeMillis(),
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

    private fun saveReadingPosition(book: Book) {
        library.updatePosition(book.id, book.currentChapter, book.chapterProgress)
    }

    private fun replaceBook(book: Book) {
        val index = books.indexOfFirst { it.id == book.id }
        if (index >= 0) books[index] = book
    }

    private fun closeReader() {
        currentBook?.let { book ->
            saveReadingPosition(book)
            // Keep the library order: the book just read goes first.
            val index = books.indexOfFirst { it.id == book.id }
            if (index > 0) {
                books.removeAt(index)
                books.add(0, book)
            }
        }
        currentBook = null
    }

    private fun deleteBook(id: String) {
        if (narration.bookId == id && narration.active) NarrationController.stop(this)
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

    private fun exportTo(uri: Uri, format: String) {
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
