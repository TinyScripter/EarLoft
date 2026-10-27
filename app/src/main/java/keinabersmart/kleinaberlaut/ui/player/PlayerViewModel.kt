package keinabersmart.kleinaberlaut.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LyricsRepository
import keinabersmart.kleinaberlaut.core.model.Lyrics
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
    private val lyricsRepository: LyricsRepository,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playbackConnection.state

    @OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentFavorite: StateFlow<Boolean> = state
        .map { it.current?.songId }
        .distinctUntilChanged()
        .flatMapLatest { songId -> songId?.let(favoritesRepository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lyricsVisible = MutableStateFlow(false)
    val lyricsVisible: StateFlow<Boolean> = _lyricsVisible.asStateFlow()
    private val lyricsRetry = MutableStateFlow(0)

    /** Songtext des aktuellen Titels; `null` während des Ladens. Geladen wird erst, wenn er angezeigt wird. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<Lyrics?> = combine(_lyricsVisible, state.map { it.current?.songId }.distinctUntilChanged(), lyricsRetry) { visible, songId, _ ->
        songId.takeIf { visible }
    }
        .flatMapLatest { songId ->
            if (songId == null) flowOf<Lyrics?>(null) else flow<Lyrics?> {
                emit(null)
                emit(lyricsRepository.lyrics(songId))
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleLyrics() {
        _lyricsVisible.value = !_lyricsVisible.value
    }

    fun retryLyrics() {
        lyricsRetry.value++
    }

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
