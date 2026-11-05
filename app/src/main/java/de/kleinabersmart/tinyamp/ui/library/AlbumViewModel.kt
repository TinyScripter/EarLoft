package de.kleinabersmart.tinyamp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.repository.AlbumColorRepository
import de.kleinabersmart.tinyamp.core.data.repository.FavoritesRepository
import de.kleinabersmart.tinyamp.core.data.repository.LibraryRepository
import de.kleinabersmart.tinyamp.core.download.DownloadRepository
import de.kleinabersmart.tinyamp.core.model.AlbumDetails
import de.kleinabersmart.tinyamp.core.model.AlbumOfflineStatus
import de.kleinabersmart.tinyamp.core.model.Song
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.components.SongActions
import de.kleinabersmart.tinyamp.ui.asLoadStateOrMissing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        .asLoadStateOrMissing()
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
