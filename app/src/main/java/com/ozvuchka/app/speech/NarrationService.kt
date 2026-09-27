package com.ozvuchka.app.speech

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.ozvuchka.app.R
import com.ozvuchka.app.data.Book
import com.ozvuchka.app.data.LibraryStore
import com.ozvuchka.app.data.chapterProgressOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicReference

enum class NarrationPhase { IDLE, PREPARING, PLAYING, PAUSED, ERROR }

data class NarrationState(
    val phase: NarrationPhase = NarrationPhase.IDLE,
    val bookId: String? = null,
    val bookTitle: String? = null,
    val chapterIndex: Int? = null,
    val chapterTitle: String? = null,
    /** -1 while the chapter title is spoken. */
    val paragraphIndex: Int? = null,
    val textOffset: Int = 0,
    val textLength: Int = 0,
    /** The word being spoken inside the sentence, when the voice reports words; -1 otherwise. */
    val wordOffset: Int = -1,
    val wordLength: Int = 0,
    val language: String = "ru",
    val message: String? = null,
    val sleepEndsAt: Long? = null,
    val sleepAtChapterEnd: Boolean = false,
    val isPreview: Boolean = false,
    /** Changes with every new message, so the UI can show the same text twice. */
    val messageId: Long = 0,
) {
    val active: Boolean get() = phase == NarrationPhase.PREPARING || phase == NarrationPhase.PLAYING || phase == NarrationPhase.PAUSED
    val isPlaying: Boolean get() = phase == NarrationPhase.PLAYING || phase == NarrationPhase.PREPARING
}

/**
 * Entry point for narration. Calls come from the visible Activity; the book stays in this process
 * and only an action goes through the Intent, which avoids Binder limits for long books.
 */
object NarrationController {
    internal class PlayRequest(
        val book: Book?,
        val chapter: Int,
        val paragraph: Int,
        val offset: Int,
        val preview: SpeechSegment? = null,
        val previewVoice: VoiceChoice? = null,
    )

    private val mutableState = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = mutableState
    private val pending = AtomicReference<PlayRequest?>(null)

    /** Reads [book] from a paragraph and character offset; continues through later chapters. */
    fun playBook(context: Context, book: Book, chapter: Int, paragraph: Int, offset: Int): Boolean {
        if (book.chapters.all { chapterOf -> chapterOf.paragraphs.all { it.isBlank() } }) {
            publish(NarrationState(phase = NarrationPhase.ERROR, bookId = book.id, message = "В книге нет текста для озвучки"))
            return false
        }
        pending.set(PlayRequest(book, chapter.coerceIn(book.chapters.indices), paragraph, offset))
        return start(context, NarrationService.ACTION_PLAY)
    }

    /** Plays a short sample with [voice] without touching reading positions. */
    fun preview(context: Context, voice: VoiceChoice, language: String, text: String): Boolean {
        val segment = SpeechSegment(-1, -2, 0, text.length, text, language, SegmentPause.SENTENCE)
        pending.set(PlayRequest(null, 0, 0, 0, preview = segment, previewVoice = voice))
        return start(context, NarrationService.ACTION_PLAY)
    }

    fun pause(context: Context) = command(context, NarrationService.ACTION_PAUSE)
    fun resume(context: Context) = command(context, NarrationService.ACTION_RESUME)
    fun toggle(context: Context) = command(context, NarrationService.ACTION_TOGGLE)
    fun stop(context: Context) = command(context, NarrationService.ACTION_STOP)
    fun next(context: Context) = command(context, NarrationService.ACTION_NEXT)
    fun previous(context: Context) = command(context, NarrationService.ACTION_PREVIOUS)

    /** Minutes until a gentle fade-out; 0 turns the timer off; -1 stops at the end of the chapter. */
    fun setSleepTimer(context: Context, minutes: Int) {
        if (!state.value.active) return
        val app = context.applicationContext
        app.startService(
            Intent(app, NarrationService::class.java)
                .setAction(NarrationService.ACTION_SLEEP)
                .putExtra(NarrationService.EXTRA_MINUTES, minutes)
        )
    }

    /** Re-reads speed and voices; the current sentence restarts with them. */
    fun settingsChanged(context: Context) = command(context, NarrationService.ACTION_SETTINGS)

    internal fun take(): PlayRequest? = pending.getAndSet(null)

    internal fun publish(next: NarrationState) {
        val previous = mutableState.value
        val messageId = if (next.message != null && next.message != previous.message) SystemClock.elapsedRealtime() else previous.messageId
        mutableState.value = next.copy(messageId = messageId)
    }

    internal fun clearMessage() {
        mutableState.value = mutableState.value.copy(message = null)
    }

    private fun start(context: Context, action: String): Boolean {
        val app = context.applicationContext
        return try {
            app.startForegroundService(Intent(app, NarrationService::class.java).setAction(action))
            true
        } catch (error: Exception) {
            pending.set(null)
            publish(NarrationState(phase = NarrationPhase.ERROR, message = "Не удалось запустить озвучку: ${error.message}"))
            false
        }
    }

    private fun command(context: Context, action: String) {
        if (!state.value.active) return
        val app = context.applicationContext
        runCatching { app.startService(Intent(app, NarrationService::class.java).setAction(action)) }
    }
}

/** Owns the synthesizers, the player and the MediaSession, so narration survives the Activity. */
class NarrationService : Service(), NarrationPlayer.Listener {
    private lateinit var hub: SynthesisHub
    private lateinit var player: NarrationPlayer
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private lateinit var audioManager: AudioManager
    private val handler = Handler(Looper.getMainLooper())
    private val library by lazy { LibraryStore(this) }

    private var book: Book? = null
    private var source: BookSegmentSource? = null
    private var previewSource: SegmentSource? = null
    private var settings: SpeechSettings? = null
    private var state = NarrationState()
    private var foreground = false
    private var focusRequest: AudioFocusRequest? = null
    private var resumeOnFocusGain = false
    private var noisyRegistered = false
    private var lastSavedAt = 0L
    private var lastIndex = 0
    private var sleepRunnable: Runnable? = null
    private var shownChapter: Int? = null

    private val idleStop = Runnable { finish(save = true) }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pausePlayback()
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        handler.post {
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> if (resumeOnFocusGain) {
                    resumeOnFocusGain = false
                    resumePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    if (state.phase == NarrationPhase.PLAYING || state.phase == NarrationPhase.PREPARING) {
                        resumeOnFocusGain = true
                        pausePlayback(keepFocus = true)
                    }
                }
                AudioManager.AUDIOFOCUS_LOSS -> {
                    resumeOnFocusGain = false
                    pausePlayback()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        hub = SynthesisHub.shared(this)
        hub.hold()
        player = NarrationPlayer(hub, this)
        audioManager = getSystemService(AudioManager::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Озвучка книг", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
            }
        )
        mediaSession = MediaSession(this, "OzvuchkaNarration")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = resumePlayback()
            override fun onPause() = pausePlayback()
            override fun onStop() = finish(save = true)
            override fun onSkipToNext() = skip(+1)
            override fun onSkipToPrevious() = skip(-1)
            override fun onFastForward() = skip(+1)
            override fun onRewind() = skip(-1)
        })
        mediaSession.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val request = NarrationController.take()
                when {
                    request != null -> startRequest(request)
                    !player.isActive -> {
                        // Started with startForegroundService(): Android requires startForeground()
                        // even when there turns out to be nothing to play.
                        if (ensureForeground()) {
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            foreground = false
                        }
                        stopSelf(startId)
                    }
                }
            }
            ACTION_PAUSE -> if (player.isActive) pausePlayback() else stopSelf(startId)
            ACTION_RESUME -> if (player.isActive) resumePlayback() else stopSelf(startId)
            ACTION_TOGGLE -> when {
                !player.isActive -> stopSelf(startId)
                player.isPaused -> resumePlayback()
                else -> pausePlayback()
            }
            ACTION_NEXT -> skip(+1)
            ACTION_PREVIOUS -> skip(-1)
            ACTION_STOP -> finish(save = true)
            ACTION_SLEEP -> setSleep(intent.getIntExtra(EXTRA_MINUTES, 0))
            ACTION_SETTINGS -> restartWithNewSettings()
            else -> if (!player.isActive) stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        player.stop()
        // Keep the voice warm for a few minutes: pressing play again starts at once.
        hub.unhold()
        abandonFocus()
        unregisterNoisy()
        mediaSession.isActive = false
        mediaSession.release()
        if (state.active) publish(state.copy(phase = NarrationPhase.IDLE))
        super.onDestroy()
    }

    // ---------------------------------------------------------------- commands

    private fun startRequest(request: NarrationController.PlayRequest) {
        hub.hold()
        handler.removeCallbacks(idleStop)
        cancelSleep(publishState = false)
        // A new book, chapter or preview replaces the session: keep where the old one was.
        if (player.isActive && book != null) saveNow()
        val loaded = SpeechSettings.load(this)
        val preview = request.preview
        if (preview != null) {
            book = null
            source = null
            val voice = request.previewVoice ?: loaded.voiceFor(preview.language)
            settings = if (preview.language == "en") loaded.copy(englishVoice = voice) else loaded.copy(russianVoice = voice)
            previewSource = SingleSegmentSource(preview)
            state = NarrationState(phase = NarrationPhase.PREPARING, bookTitle = "Пример голоса", isPreview = true)
        } else {
            val target = request.book ?: return
            book = target
            previewSource = null
            settings = loaded
            val bookSource = BookSegmentSource(target.chapters, request.chapter)
            source = bookSource
            lastIndex = bookSource.indexOf(request.chapter, request.paragraph, request.offset)
            val chapter = target.chapters.getOrNull(request.chapter)
            state = NarrationState(
                phase = NarrationPhase.PREPARING,
                bookId = target.id,
                bookTitle = target.title,
                chapterIndex = request.chapter,
                chapterTitle = chapter?.title,
            )
        }
        if (!ensureForeground()) return
        requestFocus()
        registerNoisy()
        publish(state)
        updateSession()
        val activeSettings = settings ?: return
        val voiceCheck = activeSettings.voiceFor(preview?.language ?: dominantBookLanguage())
        if (!hub.isAvailable(voiceCheck, activeSettings) && !hub.isAvailable(VoiceChoice(VoiceEngine.SUPERTONIC), activeSettings)) {
            finish(save = false, error = "Сначала скачайте голос в настройках озвучки")
            return
        }
        if (preview != null) {
            player.play(previewSource!!, 0, activeSettings)
        } else {
            player.play(source!!, lastIndex, activeSettings)
        }
    }

    private fun dominantBookLanguage(): String {
        val target = book ?: return "ru"
        val chapter = state.chapterIndex ?: 0
        return dominantLanguage(target.chapters.getOrNull(chapter)?.paragraphs.orEmpty())
    }

    private fun pausePlayback(keepFocus: Boolean = false) {
        if (!player.isActive || player.isPaused) return
        player.pause()
        onPaused(keepFocus)
    }

    /** Bookkeeping after the player stopped sound, whoever paused it. */
    private fun onPaused(keepFocus: Boolean = false) {
        if (!keepFocus) abandonFocus()
        saveNow()
        publish(state.copy(phase = NarrationPhase.PAUSED))
        updateSession()
        handler.removeCallbacks(idleStop)
        handler.postDelayed(idleStop, IDLE_STOP_MS)
    }

    private fun resumePlayback() {
        if (!player.isActive) return
        if (!requestFocus()) {
            publish(state.copy(message = "Звук занят другим приложением или звонком"))
            return
        }
        handler.removeCallbacks(idleStop)
        resumeOnFocusGain = false
        player.resume()
        publish(state.copy(phase = NarrationPhase.PLAYING, message = null))
        updateSession()
    }

    private fun skip(direction: Int) {
        val currentSource = source ?: return
        val activeSettings = settings ?: return
        val current = player.currentIndex ?: lastIndex
        val target = when {
            direction > 0 -> current + 1
            // Like a music player: «back» restarts a sentence that has been playing for a while.
            player.secondsIntoCurrent > 2.5f -> current
            else -> (current - 1).coerceAtLeast(0)
        }
        if (currentSource.get(target) == null) return
        handler.removeCallbacks(idleStop)
        requestFocus()
        lastIndex = target
        player.play(currentSource, target, activeSettings, stopAtChapterEnd = state.sleepAtChapterEnd)
        publish(state.copy(phase = NarrationPhase.PREPARING))
        updateSession()
    }

    private fun restartWithNewSettings() {
        val loaded = SpeechSettings.load(this)
        val currentSource = source
        if (currentSource == null || !player.isActive) {
            settings = loaded
            return
        }
        settings = loaded
        val wasPaused = player.isPaused
        val index = player.currentIndex ?: lastIndex
        player.play(currentSource, index, loaded, stopAtChapterEnd = state.sleepAtChapterEnd)
        if (wasPaused) {
            player.pause()
        } else {
            publish(state.copy(phase = NarrationPhase.PREPARING))
        }
    }

    private fun setSleep(minutes: Int) {
        cancelSleep(publishState = false)
        when {
            minutes > 0 -> {
                val runnable = Runnable {
                    sleepRunnable = null
                    player.fadeOutAndPause(6_000) { session ->
                        handler.post {
                            if (session != player.sessionId) return@post
                            publish(state.copy(sleepEndsAt = null))
                            onPaused()
                        }
                    }
                }
                sleepRunnable = runnable
                handler.postDelayed(runnable, minutes * 60_000L)
                publish(state.copy(sleepEndsAt = System.currentTimeMillis() + minutes * 60_000L, sleepAtChapterEnd = false))
            }
            minutes < 0 -> {
                player.setStopAtChapterEnd(true)
                publish(state.copy(sleepAtChapterEnd = true, sleepEndsAt = null))
            }
            else -> publish(state.copy(sleepEndsAt = null, sleepAtChapterEnd = false))
        }
    }

    private fun cancelSleep(publishState: Boolean) {
        sleepRunnable?.let { handler.removeCallbacks(it) }
        sleepRunnable = null
        player.setStopAtChapterEnd(false)
        if (publishState) publish(state.copy(sleepEndsAt = null, sleepAtChapterEnd = false))
    }

    private fun finish(save: Boolean, error: String? = null, completed: Boolean = false) {
        handler.removeCallbacks(idleStop)
        cancelSleep(publishState = false)
        if (save) saveNow(completed)
        player.stop()
        abandonFocus()
        unregisterNoisy()
        publish(
            state.copy(
                phase = if (error != null) NarrationPhase.ERROR else NarrationPhase.IDLE,
                message = error,
                sleepEndsAt = null,
                sleepAtChapterEnd = false,
            )
        )
        updateSession()
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
        }
        stopSelf()
    }

    // ---------------------------------------------------------------- player callbacks

    override fun onSegmentStarted(session: Long, index: Int, segment: SpeechSegment) {
        handler.post {
            if (session != player.sessionId) return@post
            lastIndex = index
            val target = book
            val chapter = target?.chapters?.getOrNull(segment.chapter)
            publish(
                state.copy(
                    phase = if (player.isPaused) NarrationPhase.PAUSED else NarrationPhase.PLAYING,
                    chapterIndex = if (target != null) segment.chapter else null,
                    chapterTitle = chapter?.title ?: state.chapterTitle,
                    paragraphIndex = if (target != null) segment.paragraph else null,
                    textOffset = segment.start,
                    textLength = segment.end - segment.start,
                    wordOffset = -1,
                    wordLength = 0,
                    language = segment.language,
                )
            )
            if (shownChapter != segment.chapter) {
                shownChapter = segment.chapter
                updateSession()
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastSavedAt > 10_000) saveNow()
        }
    }

    override fun onWordStarted(session: Long, index: Int, offset: Int, length: Int) {
        handler.post {
            if (session != player.sessionId || index != lastIndex) return@post
            publish(state.copy(wordOffset = offset, wordLength = length))
        }
    }

    override fun onBufferingChanged(session: Long, buffering: Boolean) {
        handler.post {
            if (session != player.sessionId || player.isPaused) return@post
            val phase = if (buffering) NarrationPhase.PREPARING else NarrationPhase.PLAYING
            if (state.phase != phase) {
                publish(state.copy(phase = phase))
                updateSession()
            }
        }
    }

    override fun onChapterBoundaryPause(session: Long, index: Int, segment: SpeechSegment) {
        handler.post {
            if (session != player.sessionId) return@post
            lastIndex = index
            publish(state.copy(sleepAtChapterEnd = false))
            onPaused()
        }
    }

    override fun onCompleted(session: Long) {
        handler.post {
            if (session != player.sessionId) return@post
            if (state.isPreview) finish(save = false) else finish(save = true, completed = true)
        }
    }

    override fun onError(session: Long, message: String) {
        handler.post {
            if (session != player.sessionId) return@post
            finish(save = true, error = "Ошибка озвучки: $message")
        }
    }

    override fun onVoiceFallback(session: Long, message: String) {
        handler.post {
            if (session == player.sessionId) publish(state.copy(message = message))
        }
    }

    // ---------------------------------------------------------------- state

    private fun publish(next: NarrationState) {
        state = next
        NarrationController.publish(next)
    }

    private fun saveNow(completed: Boolean = false) {
        val target = book ?: return
        val chapterIndex = state.chapterIndex ?: return
        val chapter = target.chapters.getOrNull(chapterIndex) ?: return
        lastSavedAt = SystemClock.elapsedRealtime()
        val progress = when {
            completed -> 1f
            (state.paragraphIndex ?: 0) < 0 -> 0f
            else -> chapterProgressOf(chapter.paragraphs, state.paragraphIndex ?: 0, state.textOffset)
        }
        try {
            library.updatePosition(target.id, chapterIndex, progress)
        } catch (error: Exception) {
            Log.w(TAG, "Could not save narration position", error)
        }
    }

    // ---------------------------------------------------------------- audio focus and output

    private fun requestFocus(): Boolean {
        val request = focusRequest ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            // Ducked speech is hard to follow; pause for navigation prompts and the like instead.
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(focusListener, handler)
            .build()
            .also { focusRequest = it }
        return audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(noisyReceiver, filter)
        }
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        runCatching { unregisterReceiver(noisyReceiver) }
        noisyRegistered = false
    }

    // ---------------------------------------------------------------- media session and notification

    private fun ensureForeground(): Boolean {
        if (foreground) return true
        return try {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            foreground = true
            true
        } catch (error: Exception) {
            publish(state.copy(phase = NarrationPhase.ERROR, message = "Не удалось начать фоновое воспроизведение: ${error.message}"))
            stopSelf()
            false
        }
    }

    private fun updateSession() {
        val chapterTitle = state.chapterTitle?.takeIf { it.isNotBlank() }
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapterTitle ?: state.bookTitle ?: "Озвучка")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, state.bookTitle ?: "Озвучка")
                .putString(MediaMetadata.METADATA_KEY_ALBUM, book?.author?.takeIf { it.isNotBlank() } ?: "Озвучка")
                .build()
        )
        val playbackState = when (state.phase) {
            NarrationPhase.PREPARING -> PlaybackState.STATE_BUFFERING
            NarrationPhase.PLAYING -> PlaybackState.STATE_PLAYING
            NarrationPhase.PAUSED -> PlaybackState.STATE_PAUSED
            NarrationPhase.ERROR -> PlaybackState.STATE_ERROR
            NarrationPhase.IDLE -> PlaybackState.STATE_STOPPED
        }
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND
                )
                .setState(playbackState, PlaybackState.PLAYBACK_POSITION_UNKNOWN, settings?.speed ?: 1f)
                .build()
        )
        if (foreground && state.active) updateNotification()
    }

    // MediaStyle notifications attached to an active MediaSession are exempt from
    // Android 13's POST_NOTIFICATIONS runtime permission requirement.
    @SuppressLint("NotificationPermission")
    private fun updateNotification() {
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val paused = state.phase == NarrationPhase.PAUSED
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this, 0, launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        val status = when (state.phase) {
            NarrationPhase.PREPARING -> "Готовлю голос…"
            NarrationPhase.PAUSED -> "На паузе"
            else -> state.bookTitle ?: "Читает нейроголос"
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_narration)
            .setContentTitle(state.chapterTitle?.takeIf { it.isNotBlank() } ?: state.bookTitle ?: "Озвучка")
            .setContentText(status)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(!paused)
            .setDeleteIntent(actionIntent(ACTION_STOP, 5))
            .addAction(action(android.R.drawable.ic_media_previous, "Назад", ACTION_PREVIOUS, 1))
            .addAction(
                if (paused) {
                    action(android.R.drawable.ic_media_play, "Продолжить", ACTION_TOGGLE, 2)
                } else {
                    action(android.R.drawable.ic_media_pause, "Пауза", ACTION_TOGGLE, 2)
                }
            )
            .addAction(action(android.R.drawable.ic_media_next, "Дальше", ACTION_NEXT, 3))
            .addAction(action(android.R.drawable.ic_menu_close_clear_cancel, "Стоп", ACTION_STOP, 4))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun action(icon: Int, title: String, command: String, requestCode: Int): Notification.Action =
        Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, icon),
            title,
            actionIntent(command, requestCode),
        ).build()

    private fun actionIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        this,
        requestCode,
        Intent(this, NarrationService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    companion object {
        const val ACTION_PLAY = "com.ozvuchka.app.action.NARRATION_PLAY"
        const val ACTION_PAUSE = "com.ozvuchka.app.action.NARRATION_PAUSE"
        const val ACTION_RESUME = "com.ozvuchka.app.action.NARRATION_RESUME"
        const val ACTION_TOGGLE = "com.ozvuchka.app.action.NARRATION_TOGGLE"
        const val ACTION_STOP = "com.ozvuchka.app.action.NARRATION_STOP"
        const val ACTION_NEXT = "com.ozvuchka.app.action.NARRATION_NEXT"
        const val ACTION_PREVIOUS = "com.ozvuchka.app.action.NARRATION_PREVIOUS"
        const val ACTION_SLEEP = "com.ozvuchka.app.action.NARRATION_SLEEP"
        const val ACTION_SETTINGS = "com.ozvuchka.app.action.NARRATION_SETTINGS"
        const val EXTRA_MINUTES = "com.ozvuchka.app.extra.MINUTES"
        private const val CHANNEL_ID = "ozvuchka_narration"
        private const val NOTIFICATION_ID = 1042
        private const val IDLE_STOP_MS = 30 * 60_000L
        private const val TAG = "OzvuchkaNarration"
    }
}
