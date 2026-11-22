package de.kleinabersmart.earloft.ui.player

import kotlinx.coroutines.flow.mapLatest
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.playback.QueueEntry
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
import de.kleinabersmart.earloft.core.playback.SavedMusicQueue
import de.kleinabersmart.earloft.ui.orOnError
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

/** Thin wrapper around [PlaybackConnection] so the player UI can get it via Hilt. */
@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
    private val lyricsRepository: LyricsRepository,
    private val albumColorRepository: AlbumColorRepository,
    private val miniPlayerVisibility: MiniPlayerVisibility,
    private val libraryRepository: LibraryRepository,
) : ViewModel() {

    /** Where "go to artist" leads from [entry]. */
    suspend fun artistsOf(entry: QueueEntry): List<Artist> = artistsOfSong(entry.songId, entry.albumId)

    /** Where "go to artist" leads from a song (player and song menus). */
    suspend fun artistsOfSong(songId: Long, albumId: Long?): List<Artist> = libraryRepository.artistsOfSong(songId, albumId)

    val state: StateFlow<PlayerState> = playbackConnection.state

    /**
     * Length of the songs still to come after the current one, in the player's order (also shuffled), for the queue's title
     * (with the rest of the current song: time left); `null` while unknown and while casting, where the player only holds a
     * window of the queue (review H9).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val queueLaterMs: StateFlow<Long?> = state
        .map { current -> current.laterIndices.mapNotNull { current.queue.getOrNull(it)?.songId }.takeUnless { current.isRemote } }
        .distinctUntilChanged()
        .mapLatest { later ->
            if (later == null) return@mapLatest null
            val durations = libraryRepository.songs(later.distinct()).associate { it.id to it.durationMs }
            later.sumOf { durations[it] ?: 0L }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The user has swiped the mini player away; applies until the next playback. */
    val miniPlayerHidden: StateFlow<Boolean> = miniPlayerVisibility.hidden

    fun hideMiniPlayer() = miniPlayerVisibility.hide(state.value.itemKey, playbackConnection.currentPositionMs)
    fun showMiniPlayer() = miniPlayerVisibility.show()

    init {
        // As soon as something plays again (from a list, the notification, via Bluetooth), the mini player is back; also if it
        // played and paused while no screen was open (Android Auto, the watch): then title or position differ from the hidden ones.
        viewModelScope.launch {
            state.collect { current ->
                when {
                    current.isPlaying -> miniPlayerVisibility.show()
                    current.hasMedia -> miniPlayerVisibility.showIfMovedOn(current.itemKey, playbackConnection.currentPositionMs)
                }
            }
        }
    }

    /** The saved music queue while a book or episode is loaded (music start). */
    val savedMusicQueue: StateFlow<SavedMusicQueue?> = playbackConnection.savedMusicQueue
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Resumes the music at title [index] of the [savedMusicQueue], replacing the spoken content. */
    fun resumeMusic(index: Int) = playbackConnection.resumeMusic(index)

    /** Primary color of the current title's album for the color scheme; `null` without album or cover. */
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

    /** Lyrics of the current title including their origin; `null` while loading. Only loaded once they are shown. */
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

    /** Remaining time of the sleep timer; `null` while none is running. */
    val sleepTimerRemainingMs: StateFlow<Long?> = playbackConnection.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun startSleepTimer(minutes: Int) = playbackConnection.startSleepTimer(minutes)

    /** Whether the running timer stops at the end of the song (for the line below the buttons). */
    val sleepTimerToEnd: StateFlow<Boolean> = playbackConnection.sleepTimerToEnd
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Sleep timer until the end of the current song, like "until the end of the chapter" for books. */
    fun startSleepTimerToEnd() = playbackConnection.startSleepTimerToEnd()
    fun cancelSleepTimer() = playbackConnection.cancelSleepTimer()

    val currentPositionMs: Long get() = playbackConnection.currentPositionMs

    /** Position jumps so the display catches up even while paused. */
    val positionJumps: Flow<Unit> = playbackConnection.positionJumps

    /** See [PlaybackConnection.playbackStarts]. */
    val playbackStarts: Flow<Unit> = playbackConnection.playbackStarts

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

/** Identifies the loaded title for [MiniPlayerVisibility] and [MiniPlayerScrollHide]. */
internal val PlayerState.itemKey: String?
    get() = spoken?.let { "book:${it.bookId}" } ?: current?.let { "song:${it.songId}" }
