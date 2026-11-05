package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import de.kleinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
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
        scope.launch {
            val prepared = playbackConnection.playBook(bookId, startPositionMs)
            when {
                prepared == null -> _events.emit(BookLaunchEvent.Failed)
                prepared.resumedFromServer && startPositionMs == null ->
                    _events.emit(BookLaunchEvent.ResumedFromServer(prepared.startPositionMs))
            }
        }
    }
}

@HiltViewModel
class BookLaunchViewModel @Inject constructor(val launcher: BookLauncher) : ViewModel()
