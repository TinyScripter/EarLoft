package de.kleinabersmart.earloft.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LyricsRepository
import de.kleinabersmart.earloft.core.data.repository.SongLyrics
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.playback.PlayerState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
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
    private val albumColorRepository: AlbumColorRepository,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playbackConnection.state

    /** Grundfarbe des Albums des aktuellen Titels für das Farbschema; `null` ohne Album oder Cover. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val seedColor: StateFlow<Int?> = state
        .map { it.current?.albumId }
        .distinctUntilChanged()
        .flatMapLatest { albumId -> albumId?.let(albumColorRepository::seedColor) ?: flowOf(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val isCurrentFavorite: StateFlow<Boolean> = state
        .map { it.current?.songId }
        .distinctUntilChanged()
        .flatMapLatest { songId -> songId?.let(favoritesRepository::isFavorite) ?: flowOf(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lyricsVisible = MutableStateFlow(false)
    val lyricsVisible: StateFlow<Boolean> = _lyricsVisible.asStateFlow()
    private val lyricsRetry = MutableStateFlow(0)

    /** Songtext des aktuellen Titels samt Herkunft; `null` während des Ladens. Geladen wird erst, wenn er angezeigt wird. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val lyrics: StateFlow<SongLyrics?> = combine(_lyricsVisible, state.map { it.current?.songId }.distinctUntilChanged(), lyricsRetry) { visible, songId, _ ->
        songId.takeIf { visible }
    }
        .flatMapLatest { songId ->
            if (songId == null) flowOf<SongLyrics?>(null) else flow<SongLyrics?> {
                emit(null)
                emit(lyricsRepository.songLyrics(songId))
            }.catch { emit(SongLyrics(Lyrics.Unavailable)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleLyrics() {
        _lyricsVisible.value = !_lyricsVisible.value
    }

    fun retryLyrics() {
        lyricsRetry.value++
    }

    /** Restzeit des Einschlaf-Timers; `null`, solange keiner läuft. */
    val sleepTimerRemainingMs: StateFlow<Long?> = playbackConnection.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startSleepTimer(minutes: Int) = playbackConnection.startSleepTimer(minutes)
    fun cancelSleepTimer() = playbackConnection.cancelSleepTimer()

    val currentPositionMs: Long get() = playbackConnection.currentPositionMs

    /** Sprünge der Position, damit die Anzeige auch pausiert nachzieht. */
    val positionJumps: Flow<Unit> = playbackConnection.positionJumps

    fun togglePlayPause() = playbackConnection.togglePlayPause()
    fun next() = playbackConnection.next()
    fun previous() = playbackConnection.previous()
    fun seekTo(positionMs: Long) = playbackConnection.seekTo(positionMs)
    fun toggleShuffle() = playbackConnection.toggleShuffle()
    fun cycleRepeatMode() = playbackConnection.cycleRepeatMode()
    fun skipToQueueItem(index: Int) = playbackConnection.skipToQueueItem(index)
    fun removeQueueItem(index: Int) = playbackConnection.removeQueueItem(index)
    fun moveQueueItem(from: Int, to: Int) = playbackConnection.moveQueueItem(from, to)
    fun clearQueue() = playbackConnection.clearQueue()

    fun toggleFavorite() {
        val songId = state.value.current?.songId ?: return
        viewModelScope.launch { favoritesRepository.toggleFavorite(songId) }
    }
}
