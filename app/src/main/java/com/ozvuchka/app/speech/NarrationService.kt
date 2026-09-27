package com.ozvuchka.app.speech

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.util.Log
import com.ozvuchka.app.data.LibraryStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class NarrationState(
    val title: String? = null,
    val speech: SpeechStatus = SpeechStatus(SpeechPhase.MODEL_MISSING),
    val active: Boolean = false,
    val bookId: String? = null,
    val chapterIndex: Int? = null,
    val paragraphIndex: Int? = null,
    val currentText: String? = null,
    val textOffset: Int = 0,
    val textLength: Int = 0,
)

data class NarrationChapter(
    val title: String,
    val text: String,
    val language: String = "ru",
    val bookId: String? = null,
    val chapterIndex: Int? = null,
    val paragraphs: List<String> = listOf(text),
    val startParagraphIndex: Int = 0,
    val startTextOffset: Int = 0,
    val totalParagraphs: Int = paragraphs.size,
)

/**
 * Entry point for a foreground chapter narration. Call [play] from a visible Activity
 * in response to a user gesture. The text stays in this process and only an opaque ID
 * goes through Intent, avoiding Android's Binder size limit for long chapters.
 */
object NarrationController {
    private data class Request(
        val chapters: List<NarrationChapter>,
        val speed: Float,
        val voiceId: Int,
    )

    private val requests = ConcurrentHashMap<String, Request>()
    private val mutableState = MutableStateFlow(NarrationState())
    val state: StateFlow<NarrationState> = mutableState

    fun play(
        context: Context,
        title: String,
        text: String,
        language: String = "ru",
        speed: Float = 1.0f,
        voiceId: Int = 0,
        bookId: String? = null,
        chapterIndex: Int? = null,
    ): Boolean = playChapters(
        context = context,
        chapters = listOf(NarrationChapter(title, text, language, bookId, chapterIndex)),
        speed = speed,
        voiceId = voiceId,
    )

    /** Play all provided chapters in order, including while the Activity is closed. */
    fun playChapters(
        context: Context,
        chapters: List<NarrationChapter>,
        speed: Float = 1.0f,
        voiceId: Int = 0,
    ): Boolean {
        val app = context.applicationContext
        val readableChapters = chapters.filter { it.text.isNotBlank() }
        val title = readableChapters.firstOrNull()?.title ?: chapters.firstOrNull()?.title
        if (readableChapters.isEmpty()) {
            mutableState.value = NarrationState(
                title,
                SpeechStatus(SpeechPhase.ERROR, "В главах нет текста для озвучки"),
            )
            return false
        }
        if (!SpeechModelStore(app.filesDir).isInstalled()) {
            mutableState.value = NarrationState(
                title,
                SpeechStatus(SpeechPhase.MODEL_MISSING, "Сначала загрузите голосовую модель"),
            )
            return false
        }
        val id = UUID.randomUUID().toString()
        requests[id] = Request(readableChapters, speed, voiceId)
        mutableState.value = NarrationState(
            title,
            SpeechStatus(SpeechPhase.LOADING),
            active = true,
            bookId = readableChapters.first().bookId,
            chapterIndex = readableChapters.first().chapterIndex,
        )
        return try {
            app.startForegroundService(
                Intent(app, NarrationService::class.java)
                    .setAction(NarrationService.ACTION_PLAY)
                    .putExtra(NarrationService.EXTRA_REQUEST_ID, id)
            )
            true
        } catch (error: Exception) {
            requests.remove(id)
            mutableState.value = NarrationState(
                title,
                SpeechStatus(SpeechPhase.ERROR, "Не удалось запустить озвучку: ${error.message}"),
            )
            false
        }
    }

    fun pause(context: Context) = command(context, NarrationService.ACTION_PAUSE)
    fun resume(context: Context) = command(context, NarrationService.ACTION_RESUME)
    fun stop(context: Context) = command(context, NarrationService.ACTION_STOP)

    private fun command(context: Context, action: String) {
        if (!mutableState.value.active) return
        val app = context.applicationContext
        app.startService(Intent(app, NarrationService::class.java).setAction(action))
    }

    internal fun take(id: String?): NarrationRequest? {
        if (id == null) return null
        val request = requests.remove(id) ?: return null
        return NarrationRequest(request.chapters, request.speed, request.voiceId)
    }

    internal fun update(
        title: String?,
        speech: SpeechStatus,
        active: Boolean,
        bookId: String? = null,
        chapterIndex: Int? = null,
        paragraphIndex: Int? = null,
    ) {
        mutableState.value = NarrationState(
            title, speech, active, bookId, chapterIndex, paragraphIndex,
            speech.currentText, speech.textOffset, speech.textLength,
        )
    }
}

internal data class NarrationRequest(
    val chapters: List<NarrationChapter>,
    val speed: Float,
    val voiceId: Int,
)

/** Owns the synthesizer and MediaSession, so narration survives Activity destruction. */
class NarrationService : Service() {
    private lateinit var engine: NeuralSpeechEngine
    private lateinit var mediaSession: MediaSession
    private lateinit var notificationManager: NotificationManager
    private var chapterTitle: String = "Озвучка"
    private var playbackGeneration = 0L
    private var active = false
    private var finishing = false
    private var chapters: List<NarrationChapter> = emptyList()
    private var chapterIndex = 0
    private var paragraphCursor = 0
    private var speed = 1.0f
    private var voiceId = 0
    private val libraryStore by lazy { LibraryStore(this) }

    override fun onCreate() {
        super.onCreate()
        engine = NeuralSpeechEngine(this) {
            getSharedPreferences("reader", MODE_PRIVATE).getBoolean("preferFullVoice", true)
        }
        notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Озвучка книг", NotificationManager.IMPORTANCE_LOW)
        )
        mediaSession = MediaSession(this, "OzvuchkaNarration")
        mediaSession.setFlags(
            MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
        )
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { engine.resume() }
            override fun onPause() { engine.pause() }
            override fun onStop() { finishNarration() }
        })
        mediaSession.isActive = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val request = NarrationController.take(intent.getStringExtra(EXTRA_REQUEST_ID))
                if (request == null) {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                startNarration(request)
            }
            ACTION_PAUSE -> if (active) engine.pause() else stopSelf(startId)
            ACTION_RESUME -> if (active) engine.resume() else stopSelf(startId)
            ACTION_STOP -> finishNarration()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        playbackGeneration++
        engine.onStatus = null
        engine.close()
        mediaSession.isActive = false
        mediaSession.release()
        if (active && NarrationController.state.value.speech.phase != SpeechPhase.ERROR) {
            updateController(SpeechStatus(SpeechPhase.READY), active = false)
        }
        active = false
        super.onDestroy()
    }

    private fun startNarration(request: NarrationRequest) {
        chapters = request.chapters
        speed = request.speed
        voiceId = request.voiceId
        startChapter(0, first = true)
    }

    private fun startChapter(index: Int, first: Boolean) {
        val chapter = chapters.getOrNull(index) ?: run {
            finishNarration()
            return
        }
        chapterIndex = index
        paragraphCursor = 0
        if (!first) persistPosition(chapter, 0f)
        playbackGeneration++
        val generation = playbackGeneration
        finishing = false
        engine.onStatus = null
        engine.stop()
        chapterTitle = chapter.title.ifBlank { "Озвучка" }
        active = true
        val loading = SpeechStatus(SpeechPhase.LOADING)
        updateController(loading, active = true)
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, chapterTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, "Озвучка")
                .build()
        )
        updateMediaState(loading)
        if (first) {
            // Android 14+ requires the mediaPlayback type and its manifest permission.
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(loading),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } catch (error: Exception) {
                active = false
                updateController(
                    SpeechStatus(SpeechPhase.ERROR, "Не удалось начать фоновое воспроизведение: ${error.message}"),
                    active = false,
                )
                stopSelf()
                return
            }
        } else {
            updateNotification(loading)
        }
        engine.onStatus = { status ->
            if (generation == playbackGeneration && active) handleSpeechStatus(status)
        }
        startParagraph(0)
    }

    private fun startParagraph(index: Int) {
        val chapter = chapters.getOrNull(chapterIndex) ?: return
        val next = chapter.paragraphs.indices.firstOrNull { it >= index && chapter.paragraphs[it].isNotBlank() }
        if (next == null) {
            if (chapterIndex + 1 < chapters.size) startChapter(chapterIndex + 1, first = false)
            else finishNarration(completed = true)
            return
        }
        paragraphCursor = next
        val firstParagraphFraction = if (next == 0 && chapter.startTextOffset > 0) {
            val originalLength = chapter.startTextOffset + chapter.paragraphs[0].length
            chapter.startTextOffset.toFloat() / originalLength.coerceAtLeast(1)
        } else 0f
        val progress = (chapter.startParagraphIndex + next + firstParagraphFraction) /
            chapter.totalParagraphs.coerceAtLeast(1)
        persistPosition(chapter, progress)
        val loading = SpeechStatus(SpeechPhase.LOADING)
        updateController(loading, active = true)
        updateMediaState(loading)
        try {
            engine.speak(chapter.paragraphs[next], chapter.language, voiceId, speed)
        } catch (error: Exception) {
            finishNarration(SpeechStatus(SpeechPhase.ERROR, "Ошибка запуска озвучки: ${error.message}"))
        }
    }

    private fun handleSpeechStatus(status: SpeechStatus) {
        if (finishing) return
        when (status.phase) {
            SpeechPhase.READY -> {
                startParagraph(paragraphCursor + 1)
            }
            SpeechPhase.ERROR, SpeechPhase.MODEL_MISSING -> finishNarration(status)
            else -> {
                updateController(status, active = true)
                updateMediaState(status)
                updateNotification(status)
            }
        }
    }

    // MediaStyle notifications attached to an active MediaSession are exempt from
    // Android 13's POST_NOTIFICATIONS runtime permission requirement.
    @SuppressLint("NotificationPermission")
    private fun updateNotification(status: SpeechStatus) {
        notificationManager.notify(NOTIFICATION_ID, buildNotification(status))
    }

    private fun finishNarration(error: SpeechStatus? = null, completed: Boolean = false) {
        if (finishing) return
        finishing = true
        playbackGeneration++
        engine.onStatus = null
        engine.stop()
        if (completed) chapters.getOrNull(chapterIndex)?.let { persistPosition(it, 1f) }
        active = false
        val terminal = error?.let {
            if (it.phase == SpeechPhase.MODEL_MISSING) {
                SpeechStatus(SpeechPhase.ERROR, it.message)
            } else it
        } ?: SpeechStatus(SpeechPhase.READY)
        updateController(terminal, active = false)
        updateMediaState(terminal)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateController(status: SpeechStatus, active: Boolean) {
        val chapter = chapters.getOrNull(chapterIndex)
        val absoluteStatus = if (paragraphCursor == 0 && chapter != null &&
            chapter.startTextOffset > 0
        ) status.copy(textOffset = status.textOffset + chapter.startTextOffset) else status
        NarrationController.update(
            chapterTitle,
            absoluteStatus,
            active,
            bookId = chapter?.bookId,
            chapterIndex = chapter?.chapterIndex,
            paragraphIndex = chapter?.let { it.startParagraphIndex + paragraphCursor },
        )
    }

    private fun persistPosition(chapter: NarrationChapter, progress: Float) {
        val bookId = chapter.bookId ?: return
        val index = chapter.chapterIndex ?: return
        try {
            libraryStore.updatePosition(bookId, index, progress)
        } catch (error: Exception) {
            Log.w("OzvuchkaNarration", "Could not save chapter boundary", error)
        }
    }

    private fun updateMediaState(status: SpeechStatus) {
        val state = when (status.phase) {
            SpeechPhase.LOADING -> PlaybackState.STATE_BUFFERING
            SpeechPhase.SPEAKING -> PlaybackState.STATE_PLAYING
            SpeechPhase.PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        mediaSession.setPlaybackState(
            PlaybackState.Builder()
                .setActions(actions)
                .setState(state, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )
    }

    private fun buildNotification(status: SpeechStatus): Notification {
        val paused = status.phase == SpeechPhase.PAUSED
        val toggleAction = if (paused) ACTION_RESUME else ACTION_PAUSE
        val toggleIcon = if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val toggleLabel = if (paused) "Продолжить" else "Пауза"
        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            PendingIntent.getActivity(
                this, 0, launch,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(chapterTitle)
            .setContentText(
                when (status.phase) {
                    SpeechPhase.LOADING -> "Подготовка голоса"
                    SpeechPhase.PAUSED -> "На паузе"
                    else -> "Читает нейроголос"
                }
            )
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .addAction(toggleIcon, toggleLabel, actionIntent(toggleAction, 1))
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Остановить", actionIntent(ACTION_STOP, 2))
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1)
            )
            .build()
    }

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
        const val ACTION_STOP = "com.ozvuchka.app.action.NARRATION_STOP"
        const val EXTRA_REQUEST_ID = "com.ozvuchka.app.extra.NARRATION_REQUEST_ID"
        private const val CHANNEL_ID = "ozvuchka_narration"
        private const val NOTIFICATION_ID = 1042
    }
}
