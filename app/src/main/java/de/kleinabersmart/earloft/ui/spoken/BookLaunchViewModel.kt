package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface BookLaunchEvent {
    data object Failed : BookLaunchEvent
    data class ResumedFromServer(val positionMs: Long) : BookLaunchEvent
}

/** Startet Bücher aus der UI; meldet, wenn es nicht klappt oder der Stand eines anderen Geräts übernommen wurde. */
@Singleton
class BookLauncher @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val _events = MutableSharedFlow<BookLaunchEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<BookLaunchEvent> = _events.asSharedFlow()

    fun play(bookId: Long, startPositionMs: Long? = null) {
        scope.launch { start(bookId, startPositionMs) }
    }

    /**
     * Wie [play], wartet aber, bis das Buch läuft; `false`, wenn es sich nicht starten ließ. Der Start läuft im App-Scope
     * zu Ende, auch wenn der Aufrufer vorher verschwindet.
     */
    suspend fun playAndAwait(bookId: Long): Boolean = scope.async { start(bookId, null) }.await()

    private suspend fun start(bookId: Long, startPositionMs: Long?): Boolean {
        val prepared = playbackConnection.playBook(bookId, startPositionMs)
        when {
            prepared == null -> _events.emit(BookLaunchEvent.Failed)
            prepared.resumedFromServer && startPositionMs == null ->
                _events.emit(BookLaunchEvent.ResumedFromServer(prepared.startPositionMs))
        }
        return prepared != null
    }
}

@HiltViewModel
class BookLaunchViewModel @Inject constructor(val launcher: BookLauncher) : ViewModel()
