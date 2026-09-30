package com.ozvuchka.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ozvuchka.app.conversion.BookExporter
import com.ozvuchka.app.conversion.ExportPicture
import com.ozvuchka.app.data.Annotation
import com.ozvuchka.app.data.AnnotationStore
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.BookImages
import com.ozvuchka.app.data.Chapter
import com.ozvuchka.app.data.Covers
import com.ozvuchka.app.data.LibraryStore
import com.ozvuchka.app.data.SearchHit
import com.ozvuchka.app.data.chapterProgressOf
import com.ozvuchka.app.data.searchBook
import com.ozvuchka.app.importer.FileBookImporter
import com.ozvuchka.app.importer.WebChapter
import com.ozvuchka.app.importer.WebChapterImporter
import com.ozvuchka.app.importer.imageExtension
import com.ozvuchka.app.importer.toBookChapter
import com.ozvuchka.app.speech.BookCasts
import com.ozvuchka.app.speech.CastMember
import com.ozvuchka.app.speech.CastStore
import com.ozvuchka.app.speech.DialogueMode
import com.ozvuchka.app.speech.DialogueVoices
import com.ozvuchka.app.speech.EmotionLevel
import com.ozvuchka.app.speech.ModelInstallState
import com.ozvuchka.app.speech.NarrationController
import com.ozvuchka.app.speech.NarrationPhase
import com.ozvuchka.app.speech.NarrationState
import com.ozvuchka.app.speech.PronunciationDictionary
import com.ozvuchka.app.speech.PronunciationStore
import com.ozvuchka.app.speech.RuVoiceInstallState
import com.ozvuchka.app.speech.RuVoiceInstaller
import com.ozvuchka.app.speech.SpeechModel
import com.ozvuchka.app.speech.SpeechModels
import com.ozvuchka.app.speech.SpeechRole
import com.ozvuchka.app.speech.SpeechSettings
import com.ozvuchka.app.speech.SynthesisHub
import com.ozvuchka.app.speech.SystemEngineInfo
import com.ozvuchka.app.speech.SystemVoiceInfo
import com.ozvuchka.app.speech.SystemVoices
import com.ozvuchka.app.speech.VoiceCatalog
import com.ozvuchka.app.speech.VoiceChoice
import com.ozvuchka.app.speech.VoiceEngine
import com.ozvuchka.app.speech.dominantLanguage
import com.ozvuchka.app.ui.ChapterContent
import com.ozvuchka.app.ui.LibraryBookUi
import com.ozvuchka.app.ui.LibraryScreen
import com.ozvuchka.app.ui.OzvuchkaTheme
import com.ozvuchka.app.ui.PronunciationUi
import com.ozvuchka.app.ui.ReaderActions
import com.ozvuchka.app.ui.ReaderFont
import com.ozvuchka.app.ui.ReaderJump
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
    private var autoLoadWebChapters by mutableStateOf(true)
    private var readerBrightness by mutableStateOf<Float?>(null)
    private var warmLight by mutableFloatStateOf(0f)
    private var prefetchingChapterOf: String? = null
    private val annotationStore by lazy { AnnotationStore(this) }
    /** Bookmarks and highlights of the open book. */
    private var annotations by mutableStateOf<List<Annotation>>(emptyList())
    private var readerJump by mutableStateOf<ReaderJump?>(null)
    private var pronunciationCount by mutableIntStateOf(0)
    /** Characters of the open book with the voices of their lines; null until they are found. */
    private var castMembers by mutableStateOf<List<CastMember>?>(null)
    private var castRequest = 0
    private var speech by mutableStateOf<SpeechSettings?>(null)
    private var modelStates by mutableStateOf<Map<SpeechModel, ModelInstallState>>(emptyMap())
    private var installedModels by mutableStateOf<Set<SpeechModel>>(emptySet())
    private var systemEngines by mutableStateOf<List<SystemEngineInfo>>(emptyList())
    private val installedEngines: Set<String> get() = systemEngines.mapTo(HashSet()) { it.packageName }
    /** The engines were looked up at least once, so a missing RuVoice is really missing. */
    private var enginesScanned by mutableStateOf(false)
    private var ruVoiceSetup by mutableStateOf(RuVoiceInstaller.state.value)
    private var ruVoiceUpdate by mutableStateOf(RuVoiceInstaller.update.value)
    /** `versionName` of the RuVoice on the phone, null when it is not installed. */
    private var ruVoiceVersion by mutableStateOf<String?>(null)
    private var ruVoiceOfferDismissed by mutableStateOf(false)
    /** Voices each Android TTS engine reported, loaded one engine at a time when needed. */
    private var engineVoices by mutableStateOf<Map<String, List<SystemVoiceInfo>>>(emptyMap())
    private var loadingEngines by mutableStateOf<Set<String>>(emptySet())
    private var showVoices by mutableStateOf(false)
    private var voicesLanguage by mutableStateOf("ru")
    private var previewVoice by mutableStateOf<VoiceChoice?>(null)
    /** The sample dialogue now playing is the one that shows emotions. */
    private var previewingEmotions by mutableStateOf(false)
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
            RuVoiceInstaller.state.collect { state ->
                ruVoiceSetup = state
                if (state.stage == RuVoiceInstallState.Stage.DONE) onRuVoiceInstalled(state)
            }
        }
        lifecycleScope.launch {
            RuVoiceInstaller.update.collect { ruVoiceUpdate = it }
        }
        lifecycleScope.launch {
            // Android's confirmation for installing RuVoice opens over the visible reader.
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                RuVoiceInstaller.confirmation.collect { pending ->
                    if (pending == null) return@collect
                    val confirm = RuVoiceInstaller.takeConfirmation() ?: return@collect
                    runCatching { startActivity(confirm) }
                        .onFailure { notice = "Не удалось открыть установку RuVoice: ${it.message}" }
                }
            }
        }
        lifecycleScope.launch {
            // Narration fetched the next web chapter: show it in the open book and the library.
            NarrationController.bookUpdates.collect { id ->
                val fresh = withContext(Dispatchers.IO) { library.get(id) } ?: return@collect
                books.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index -> books[index] = books[index].copy(chapters = fresh.chapters) }
                currentBook?.takeIf { it.id == id }?.let { open -> currentBook = open.copy(chapters = fresh.chapters) }
            }
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
                    // The reader's brightness applies only while a book is open.
                    val brightness = if (book != null) readerBrightness?.coerceIn(0.01f, 1f) else null
                    val wanted = brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                    if (window.attributes.screenBrightness != wanted) {
                        window.attributes = window.attributes.apply { screenBrightness = wanted }
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
                            ruVoiceOffer = ruVoiceSetup.takeIf {
                                enginesScanned && VoiceCatalog.RUVOICE_PACKAGE !in installedEngines &&
                                    (!ruVoiceOfferDismissed || it.busy || it.stage == RuVoiceInstallState.Stage.FAILED)
                            },
                            ruVoiceActions = voiceActions,
                            onDismissRuVoice = ::dismissRuVoiceOffer,
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
                                    russianDialogue = settings.russianDialogue,
                                    englishDialogue = settings.englishDialogue,
                                    dialoguePreviewing = narration.isPreview && narration.active && previewVoice == null && !previewingEmotions,
                                    emotions = settings.emotions,
                                    emotionsPreviewing = narration.isPreview && narration.active && previewVoice == null && previewingEmotions,
                                    ruVoiceSetup = ruVoiceSetup,
                                    ruVoiceVersion = ruVoiceVersion,
                                    ruVoiceUpdate = ruVoiceUpdate,
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
            // Older builds only knew serif or sans; new installs start with Literata.
            font = preferences.getString("readerFont", null)?.let { name -> runCatching { ReaderFont.valueOf(name) }.getOrNull() }
                ?: when {
                    !preferences.contains("useSerif") -> ReaderFont.LITERATA
                    preferences.getBoolean("useSerif", true) -> ReaderFont.SERIF
                    else -> ReaderFont.SANS
                },
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
        autoLoadWebChapters = preferences.getBoolean("autoLoadWebChapters", true)
        readerBrightness = preferences.getFloat("readerBrightness", Float.NaN).takeUnless { it.isNaN() }
        warmLight = preferences.getFloat("warmLight", 0f)
        ruVoiceOfferDismissed = preferences.getBoolean("ruVoiceOfferDismissed", false)
    }

    private fun saveTypography(value: ReaderTypography) {
        typography = value
        preferences.edit()
            .putFloat("fontSizeSp", value.fontSizeSp)
            .putString("readerFont", value.font.name)
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
            val (engines, version) = withContext(Dispatchers.IO) {
                SystemVoices.engines(this@MainActivity) to RuVoiceInstaller.installedVersion(this@MainActivity)
            }
            val found = engines.mapTo(HashSet()) { it.packageName }
            val ruVoiceAppeared = VoiceCatalog.RUVOICE_PACKAGE in found && VoiceCatalog.RUVOICE_PACKAGE !in installedEngines
            systemEngines = engines
            ruVoiceVersion = version
            enginesScanned = true
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

    /** RuVoice was installed from the app: Russian text switches to it right away; an update keeps the chosen voice. */
    private fun onRuVoiceInstalled(finished: RuVoiceInstallState) {
        RuVoiceInstaller.acknowledge()
        if (finished.previousVersion != null) {
            refreshEngines()
            notice = "RuVoice обновлён" + (finished.version?.let { " до версии $it" } ?: "")
            return
        }
        preferences.edit().putBoolean("voiceRuChosen", true).apply()
        updateSpeech { it.copy(russianVoice = VoiceChoice(VoiceEngine.SYSTEM, enginePackage = VoiceCatalog.RUVOICE_PACKAGE)) }
        refreshEngines()
        notice = "RuVoice установлен: русский текст читает Silero v5"
    }

    private fun dismissRuVoiceOffer() {
        ruVoiceOfferDismissed = true
        preferences.edit().putBoolean("ruVoiceOfferDismissed", true).apply()
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
            if (!state.active) {
                previewVoice = null
                previewingEmotions = false
            }
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
            styles = chapter.styles,
            previousChapter = book.chapters.getOrNull(chapterIndex - 1)?.let { neighbour(chapterIndex - 1, it) },
            nextChapter = book.chapters.getOrNull(chapterIndex + 1)?.let { neighbour(chapterIndex + 1, it) },
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
            annotations = annotations,
            jump = readerJump,
            pronunciationCount = pronunciationCount,
            isWebBook = book.chapters.any { it.sourceUrl != null },
            autoLoadWebChapters = autoLoadWebChapters,
            brightness = readerBrightness,
            systemBrightness = systemBrightness(),
            warmLight = warmLight,
            characters = castMembers,
            voicesByGender = settings?.dialogueFor(language)?.mode == DialogueMode.BY_GENDER,
        )
    }

    private fun neighbour(index: Int, chapter: Chapter) =
        ChapterContent(index, chapter.title, chapter.paragraphs, chapter.styles, dominantLanguage(chapter.paragraphs))

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

        override fun changeChapter(index: Int, progress: Float) {
            val book = currentBook ?: return
            if (index !in book.chapters.indices) return
            val within = progress.coerceIn(0f, 1f)
            val updated = book.copy(currentChapter = index, chapterProgress = within)
            currentBook = updated
            replaceBook(updated)
            library.updatePosition(book.id, index, within)
            prefetchWebChapter(updated)
            // Listening continues from the place the reader turned to.
            if (narration.active && narration.bookId == book.id && !narration.isPreview) {
                val (paragraph, offset) = if (within > 0f) updated.position() else 0 to 0
                NarrationController.playBook(this@MainActivity, updated, index, paragraph, offset)
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

        override fun brightnessChanged(level: Float?, final: Boolean) {
            readerBrightness = level
            if (final) {
                preferences.edit().apply { if (level == null) remove("readerBrightness") else putFloat("readerBrightness", level) }.apply()
            }
        }

        override fun warmLightChanged(level: Float) {
            warmLight = level
            preferences.edit().putFloat("warmLight", level).apply()
        }

        override fun autoLoadWebChaptersChanged(enabled: Boolean) {
            autoLoadWebChapters = enabled
            preferences.edit().putBoolean("autoLoadWebChapters", enabled).apply()
            currentBook?.let(::prefetchWebChapter)
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

        override fun pronunciationOf(word: String): PronunciationUi? =
            PronunciationStore.find(this@MainActivity, currentBook?.id, word)?.let { (spoken, ownBook) ->
                PronunciationUi(word, spoken, everyBook = !ownBook)
            }

        override fun pronunciations(): List<PronunciationUi> {
            val id = currentBook?.id
            val own = if (id == null) emptyList() else {
                PronunciationStore.entries(this@MainActivity, id).map { PronunciationUi(it.key, it.value, everyBook = false) }
            }
            val shared = PronunciationStore.entries(this@MainActivity, null).map { PronunciationUi(it.key, it.value, everyBook = true) }
            return (own + shared).sortedBy { it.word }
        }

        override fun savePronunciation(word: String, spoken: String, everyBook: Boolean) {
            PronunciationStore.put(this@MainActivity, currentBook?.id, word, spoken, everyBook)
            refreshPronunciationCount()
            // Narration in progress picks the new pronunciation up from the current sentence.
            NarrationController.settingsChanged(this@MainActivity)
            notice = "«$word» будет звучать как «${PronunciationDictionary.display(spoken)}»"
        }

        override fun removePronunciation(word: String) {
            PronunciationStore.remove(this@MainActivity, currentBook?.id, word)
            refreshPronunciationCount()
            NarrationController.settingsChanged(this@MainActivity)
        }

        override fun previewPronunciation(word: String, spoken: String, sentence: String, language: String) {
            val settings = speech ?: SpeechSettings.load(this@MainActivity)
            val voice = settings.voiceFor(language)
            previewVoice = voice
            NarrationController.preview(this@MainActivity, voice, language, sentence, PronunciationDictionary(mapOf(word to spoken)))
        }

        override fun loadCharacters() = this@MainActivity.loadCharacters()

        override fun setCharacterGender(name: String, gender: SpeechRole?) {
            val book = currentBook ?: return
            CastStore.choose(this@MainActivity, book.id, name, gender)
            loadCharacters()
            // Narration in progress voices the character's lines anew from the current sentence.
            NarrationController.settingsChanged(this@MainActivity)
            notice = when (gender) {
                SpeechRole.MALE -> "$name: реплики читает мужской голос"
                SpeechRole.FEMALE -> "$name: реплики читает женский голос"
                else -> "$name: голос снова подсказывает текст"
            }
        }

        override fun addAnnotation(annotation: Annotation) = changeAnnotations { it + annotation }

        override fun updateAnnotation(annotation: Annotation) =
            changeAnnotations { list -> list.map { if (it.id == annotation.id) annotation else it } }

        override fun removeAnnotation(id: String) = changeAnnotations { list -> list.filterNot { it.id == id } }

        override fun jumpTo(chapter: Int, paragraph: Int, offset: Int, mark: IntRange?) {
            val book = currentBook ?: return
            val paragraphs = book.chapters.getOrNull(chapter)?.paragraphs ?: return
            val progress = if (paragraph < 0) 0f else chapterProgressOf(paragraphs, paragraph, offset)
            val updated = book.copy(currentChapter = chapter, chapterProgress = progress)
            currentBook = updated
            replaceBook(updated)
            library.updatePosition(book.id, chapter, progress)
            readerJump = ReaderJump(System.nanoTime(), chapter, paragraph, offset, mark)
        }

        override suspend fun search(query: String): List<SearchHit> {
            val book = currentBook ?: return emptyList()
            return withContext(Dispatchers.Default) { searchBook(book.chapters, query) }
        }

        override fun translate(text: String) = translateText(text)

        override fun copyText(text: String) {
            getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Цитата", text))
            // Android 13 and later confirm a copy themselves.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) notice = "Скопировано"
        }

        override fun shareQuote(text: String) {
            val book = currentBook
            val signature = listOfNotNull(book?.title, book?.author?.takeIf { it.isNotBlank() }).joinToString(", ")
            val body = "«${text.trim()}»" + if (signature.isNotBlank()) "\n— $signature" else ""
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, body)
            startActivity(Intent.createChooser(send, "Поделиться цитатой"))
        }
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
        // Asks GitHub only when RuVoice is installed, at most every few hours, and quietly if there is no network.
        RuVoiceInstaller.checkForUpdate(this)
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
        override fun installRuVoice() = RuVoiceInstaller.install(this@MainActivity)
        override fun cancelRuVoice() = RuVoiceInstaller.cancel()
        override fun checkRuVoiceUpdate() = RuVoiceInstaller.checkForUpdate(this@MainActivity, force = true)

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

        override fun setDialogue(language: String, dialogue: DialogueVoices) {
            updateSpeech { if (language == "en") it.copy(englishDialogue = dialogue) else it.copy(russianDialogue = dialogue) }
            // The open book's characters are needed now: find them before narration asks.
            if (dialogue.mode != DialogueMode.OFF && castMembers == null) loadCharacters()
        }

        override fun previewDialogue(language: String) {
            previewVoice = null
            previewingEmotions = false
            NarrationController.previewDialogue(this@MainActivity, language)
        }

        override fun setEmotions(level: EmotionLevel) = updateSpeech { it.copy(emotions = level) }

        override fun previewEmotions(language: String) {
            previewVoice = null
            previewingEmotions = true
            NarrationController.previewDialogue(this@MainActivity, language, emotional = true)
        }

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
        prefetchWebChapter(opened)
        annotations = emptyList()
        readerJump = null
        castMembers = null
        refreshPronunciationCount()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { annotationStore.list(opened.id) }.getOrDefault(emptyList()) }
            if (currentBook?.id == opened.id) annotations = loaded
        }
        // Reading the book for names takes a moment: do it now, so narration starts at once.
        if (speech?.splitsDialogue == true) loadCharacters()
    }

    /** Finds the open book's characters in the background; the list refreshes with the reader's choices. */
    private fun loadCharacters() {
        val book = currentBook ?: return
        val request = ++castRequest
        lifecycleScope.launch {
            val members = withContext(Dispatchers.Default) { BookCasts.forBook(this@MainActivity, book).members() }
            // Only the latest request counts: a choice made meanwhile is already in it.
            if (request == castRequest && currentBook?.id == book.id) castMembers = members
        }
    }

    /**
     * Reading the last chapter of a web book loads the next one quietly, so «Глава →» and narration
     * just go on. While this book is narrated the service does it instead.
     */
    private fun prefetchWebChapter(book: Book) {
        if (!autoLoadWebChapters || prefetchingChapterOf == book.id) return
        if (book.currentChapter < book.chapters.lastIndex) return
        val nextUrl = book.chapters.lastOrNull()?.nextUrl ?: return
        if (narration.active && narration.bookId == book.id && !narration.isPreview) return
        prefetchingChapterOf = book.id
        lifecycleScope.launch {
            try {
                val fetched = WebChapterImporter.importChapter(nextUrl).toBookChapter()
                val updated = withContext(Dispatchers.IO) { library.appendChapter(book.id, fetched) } ?: return@launch
                books.indexOfFirst { it.id == book.id }.takeIf { it >= 0 }?.let { index -> books[index] = books[index].copy(chapters = updated.chapters) }
                currentBook?.takeIf { it.id == book.id }?.let { open -> currentBook = open.copy(chapters = updated.chapters) }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                // Offline or the site changed: the «Загрузить» button stays for a manual try.
            } finally {
                prefetchingChapterOf = null
            }
        }
    }

    /** The system brightness setting, roughly on the window's 0..1 scale. */
    private fun systemBrightness(): Float =
        runCatching { android.provider.Settings.System.getInt(contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS) / 255f }
            .getOrDefault(0.5f).coerceIn(0.05f, 1f)

    private fun changeAnnotations(change: (List<Annotation>) -> List<Annotation>) {
        val book = currentBook ?: return
        val updated = change(annotations)
        annotations = updated
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { annotationStore.save(book.id, updated) }
                .onFailure { withContext(Dispatchers.Main) { notice = "Не удалось сохранить закладки: ${it.message}" } }
        }
    }

    private fun refreshPronunciationCount() {
        val id = currentBook?.id
        pronunciationCount = PronunciationStore.entries(this, null).size + (id?.let { PronunciationStore.entries(this, it).size } ?: 0)
    }

    /**
     * Shows a translation without leaving the book: Google Translate, Yandex, DeepL or Microsoft
     * answer the selected-text action with a popup; the web page is the fallback.
     */
    private fun translateText(text: String) {
        val intent = Intent(Intent.ACTION_PROCESS_TEXT)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
            .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
        val handlers = packageManager.queryIntentActivities(intent, 0)
        val preferred = listOf("com.google.android.apps.translate", "ru.yandex.translate", "com.deepl.mobiletranslator", "com.microsoft.translator")
        val translator = preferred.firstNotNullOfOrNull { pkg -> handlers.firstOrNull { it.activityInfo.packageName == pkg } }
        try {
            when {
                translator != null -> startActivity(intent.setClassName(translator.activityInfo.packageName, translator.activityInfo.name))
                handlers.isNotEmpty() -> startActivity(Intent.createChooser(intent, "Перевести"))
                else -> openUrl("https://translate.google.com/?sl=auto&tl=ru&text=" + Uri.encode(text))
            }
        } catch (_: ActivityNotFoundException) {
            openUrl("https://translate.google.com/?sl=auto&tl=ru&text=" + Uri.encode(text))
        }
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
        // The chapter is already on its way; the reader moves into it when it arrives.
        if (importing || prefetchingChapterOf == book.id) return
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
                val removed = withContext(Dispatchers.IO) {
                    runCatching { annotationStore.delete(id) }
                    runCatching { Covers.delete(this@MainActivity, id) }
                    runCatching { BookImages.delete(this@MainActivity, id) }
                    PronunciationStore.clearBook(this@MainActivity, id)
                    CastStore.clearBook(this@MainActivity, id)
                    BookCasts.forget(id)
                    library.delete(id)
                }
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

    /** A stored picture as JPEG, PNG or GIF: WebP pictures cut from PDF pages are converted for other readers. */
    private fun exportPicture(bookId: String, name: String): ExportPicture? {
        val bytes = BookImages.bytes(this, bookId, name) ?: return null
        return when (imageExtension(bytes)) {
            "png" -> ExportPicture(bytes, "image/png", "png")
            "jpg" -> ExportPicture(bytes, "image/jpeg", "jpg")
            "gif" -> ExportPicture(bytes, "image/gif", "gif")
            else -> {
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                // JPEG has no transparency: paint the picture on white paper first.
                val flat = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                android.graphics.Canvas(flat).apply {
                    drawColor(android.graphics.Color.WHITE)
                    drawBitmap(decoded, 0f, 0f, null)
                }
                decoded.recycle()
                val output = java.io.ByteArrayOutputStream()
                flat.compress(Bitmap.CompressFormat.JPEG, 90, output)
                flat.recycle()
                ExportPicture(output.toByteArray(), "image/jpeg", "jpg")
            }
        }
    }

    private fun exportTo(uri: Uri, format: String) {
        val book = currentBook ?: return
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { BookExporter.write(book, format, it) { name -> exportPicture(book.id, name) } }
                        ?: error("Не удалось создать файл")
                }
                notice = "Книга сохранена в формате ${format.uppercase()}"
            } catch (error: Exception) {
                notice = "Не удалось сохранить книгу: ${error.message ?: "ошибка записи"}"
            }
        }
    }
}
