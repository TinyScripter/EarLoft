package keinabersmart.kleinaberlaut.ui.player

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/** Dünne Hülle um [PlaybackConnection], damit die Player-UI per Hilt an sie kommt. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playbackConnection.state

    val currentPositionMs: Long get() = playbackConnection.currentPositionMs

    fun togglePlayPause() = playbackConnection.togglePlayPause()
    fun next() = playbackConnection.next()
    fun previous() = playbackConnection.previous()
    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)
    fun toggleShuffle() = playbackConnection.toggleShuffle()
    fun cycleRepeatMode() = playbackConnection.cycleRepeatMode()
    fun skipToQueueItem(index: Int) = playbackConnection.skipToQueueItem(index)
    fun removeQueueItem(index: Int) = playbackConnection.removeQueueItem(index)
}
