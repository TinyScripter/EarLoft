package keinabersmart.kleinaberlaut.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Dünne Hülle um [PlaybackConnection], damit die Player-UI per Hilt an sie kommt. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playbackConnection.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentFavorite: StateFlow<Boolean> = state
        .map { it.current?.songId }
        .distinctUntilChanged()
        .flatMapLatest { songId -> songId?.let(favoritesRepository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val currentPositionMs: Long get() = playbackConnection.currentPositionMs

    fun togglePlayPause() = playbackConnection.togglePlayPause()
    fun next() = playbackConnection.next()
    fun previous() = playbackConnection.previous()
    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)
    fun toggleShuffle() = playbackConnection.toggleShuffle()
    fun cycleRepeatMode() = playbackConnection.cycleRepeatMode()
    fun skipToQueueItem(index: Int) = playbackConnection.skipToQueueItem(index)
    fun removeQueueItem(index: Int) = playbackConnection.removeQueueItem(index)

    fun toggleFavorite() {
        val songId = state.value.current?.songId ?: return
        viewModelScope.launch { favoritesRepository.toggleFavorite(songId) }
    }
}
