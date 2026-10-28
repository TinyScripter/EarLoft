package keinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.AlbumColorRepository
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.AlbumDetails
import keinabersmart.kleinaberlaut.core.model.AlbumOfflineStatus
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.toLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = AlbumViewModel.Factory::class)
class AlbumViewModel @AssistedInject constructor(
    @Assisted private val albumId: Long,
    libraryRepository: LibraryRepository,
    private val downloadRepository: DownloadRepository,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
    albumColorRepository: AlbumColorRepository,
) : ViewModel(), SongActions {

    /** Grundfarbe des Covers für das Farbschema der Seite. */
    val seedColor: StateFlow<Int?> = albumColorRepository.seedColor(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val album: StateFlow<LoadState<AlbumDetails>> = libraryRepository.album(albumId)
        .map { it.toLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val offline: StateFlow<AlbumOfflineStatus> = downloadRepository.albumStatus(albumId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumOfflineStatus.EMPTY)

    fun play(startIndex: Int = 0) {
        songs()?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffle() {
        songs()?.let { playbackConnection.play(it, shuffle = true) }
    }

    fun downloadAlbum() {
        viewModelScope.launch { downloadRepository.downloadAlbum(albumId) }
    }

    fun removeAlbumDownload() {
        viewModelScope.launch { downloadRepository.removeAlbum(albumId) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private fun songs(): List<Song>? = (album.value as? LoadState.Ready)?.value?.songs

    @AssistedFactory
    interface Factory {
        fun create(albumId: Long): AlbumViewModel
    }
}
