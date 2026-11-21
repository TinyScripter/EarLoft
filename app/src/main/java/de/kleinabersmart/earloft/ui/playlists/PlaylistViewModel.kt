package de.kleinabersmart.earloft.ui.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.core.model.PlaylistDetails
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.SongActions
import de.kleinabersmart.earloft.ui.asLoadStateOrMissing
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel @AssistedInject constructor(
    @Assisted private val playlistId: Long,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playbackConnection: PlaybackConnection,
    private val downloadRepository: DownloadRepository,
) : ViewModel(), SongActions {

    val playlist: StateFlow<LoadState<PlaylistDetails>> = playlistRepository.playlist(playlistId)
        .asLoadStateOrMissing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val offline: StateFlow<AlbumOfflineStatus> = combine(
        playlistRepository.playlist(playlistId),
        downloadRepository.songStatuses(),
    ) { details, lookup -> lookup.statusOf(details?.entries?.map { it.song.id }.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumOfflineStatus.EMPTY)

    fun downloadPlaylist() {
        viewModelScope.launch { downloadRepository.downloadPlaylist(playlistId) }
    }

    fun removePlaylistDownload() {
        viewModelScope.launch { downloadRepository.removePlaylist(playlistId) }
    }

    fun play(startIndex: Int = 0) {
        songs()?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffle() {
        songs()?.let { playbackConnection.play(it, shuffle = true) }
    }

    fun playNext() {
        songs()?.let(playbackConnection::playNext)
    }

    fun addToQueue() {
        songs()?.let(playbackConnection::addToQueue)
    }

    fun rename(name: String) {
        viewModelScope.launch { playlistRepository.rename(playlistId, name) }
    }

    /** [onDeleted] only afterwards – navigating back ends the `viewModelScope`. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlistRepository.delete(playlistId)
            onDeleted()
        }
    }

    fun removeEntry(entryId: Long) {
        viewModelScope.launch { playlistRepository.removeEntry(playlistId, entryId) }
    }

    /** Saves the entry order chosen while reordering. */
    fun reorder(entryIds: List<Long>) {
        viewModelScope.launch { playlistRepository.reorder(playlistId, entryIds) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private fun songs(): List<Song>? = (playlist.value as? LoadState.Ready)?.value?.entries?.map { it.song }

    @AssistedFactory
    interface Factory {
        fun create(playlistId: Long): PlaylistViewModel
    }
}
