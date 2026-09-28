package com.ozvuchka.app.speech

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.ozvuchka.app.data.LibraryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** «Озвучка» in quick settings: pauses and resumes, or picks up the last book where it stopped. */
class NarrationTileService : TileService() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private var watching: Job? = null
    private var lastTitle: String? = null

    override fun onStartListening() {
        super.onStartListening()
        watching = scope.launch {
            lastTitle = withContext(Dispatchers.IO) {
                runCatching { LibraryStore(this@NarrationTileService).let { store -> store.lastOpenedId()?.let(store::get)?.title } }.getOrNull()
            }
            NarrationController.state.collect(::render)
        }
    }

    override fun onStopListening() {
        watching?.cancel()
        watching = null
        super.onStopListening()
    }

    private fun render(state: NarrationState) {
        val tile = qsTile ?: return
        val listening = state.active && !state.isPreview
        tile.state = if (listening && state.isPlaying) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Озвучка"
        tile.subtitle = when {
            listening && state.isPlaying -> state.bookTitle ?: "Читает"
            listening -> "На паузе"
            else -> lastTitle ?: "Продолжить"
        }
        tile.updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = NarrationController.state.value
        if (state.active && !state.isPreview) {
            NarrationController.toggle(this)
            return
        }
        scope.launch {
            val book = withContext(Dispatchers.IO) {
                runCatching { LibraryStore(this@NarrationTileService).let { store -> store.lastOpenedId()?.let(store::get) } }.getOrNull()
            }
            val started = book != null && run {
                val (paragraph, offset) = book.position()
                NarrationController.playBook(this@NarrationTileService, book, book.currentChapter, paragraph, offset)
            }
            if (!started) openApp()
        }
    }

    private fun openApp() {
        val launch = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(launch)
        }
    }
}
