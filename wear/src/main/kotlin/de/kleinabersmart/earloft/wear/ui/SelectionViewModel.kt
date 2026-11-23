package de.kleinabersmart.earloft.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.model.PlaylistDetails
import de.kleinabersmart.earloft.core.model.PlaylistEntry
import de.kleinabersmart.earloft.core.model.SongOfflineLookup
import de.kleinabersmart.earloft.core.model.SongOfflineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** `Loading` before the first value; `Missing` if the selection has been removed meanwhile; `Failed` if Room threw an exception. */
sealed interface SelectionUiState {
    data object Loading : SelectionUiState

    data object Missing : SelectionUiState

    data object Failed : SelectionUiState

    data class Ready(val details: PlaylistDetails, val offline: SongOfflineLookup) : SelectionUiState {
        val downloadedCount: Int get() = details.entries.map { it.song.id }.distinct().count { isLoaded(it) }

        /**
         * [downloadedCount] against all entries: a song twice in the playlist is downloaded once, so complete means all
         * entries, not "N−1 of N" for good (review2 J88, as G49 in the storage view).
         */
        val loadedEntries: Int get() {
            val distinct = details.entries.map { it.song.id }.distinct().size
            return downloadedCount.let { if (it >= distinct) details.playlist.songCount else it }
        }

        fun isLoaded(songId: Long) = offline[songId].state == SongOfflineState.DOWNLOADED
    }
}

/** Where the tracks of a list on the watch come from: a device playlist (selection from the phone) or a downloaded album. */
sealed interface LocalList {
    data class OfPlaylist(val id: Long) : LocalList

    data class OfAlbum(val id: Long) : LocalList
}

@HiltViewModel(assistedFactory = SelectionViewModel.Factory::class)
class SelectionViewModel @AssistedInject constructor(
    @Assisted source: LocalList,
    playlistRepository: PlaylistRepository,
    libraryRepository: LibraryRepository,
    downloadRepository: DownloadRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    private val details: Flow<PlaylistDetails?> = when (source) {
        is LocalList.OfPlaylist -> playlistRepository.playlist(source.id)
        is LocalList.OfAlbum -> libraryRepository.album(source.id).map { album ->
            album?.let {
                // An album behaves like a playlist without duplicates; the song ID serves as the entry ID.
                PlaylistDetails(
                    playlist = Playlist(it.album.id, it.album.title, it.songs.size, it.songs.sumOf { song -> song.durationMs }, null),
                    entries = it.songs.map { song -> PlaylistEntry(song.id, song, false) },
                )
            }
        }
    }

    val state: StateFlow<SelectionUiState> = combine(details, downloadRepository.songStatuses()) { details, offline ->
        details?.let { SelectionUiState.Ready(it, offline) } ?: SelectionUiState.Missing
    }
        .orOnError(SelectionUiState.Failed)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionUiState.Loading)

    /** Plays the downloaded songs of the selection; from [entryId] (duplicates in playlists stay distinguishable) or from the start. */
    fun play(entryId: Long? = null, shuffle: Boolean = false) {
        val ready = state.value as? SelectionUiState.Ready ?: return
        val entries = ready.details.entries.filter { ready.isLoaded(it.song.id) }
        if (entries.isEmpty()) return
        val startIndex = entryId?.let { id -> entries.indexOfFirst { it.entryId == id } }?.coerceAtLeast(0) ?: 0
        playback.play(entries.map { it.song }, startIndex, shuffle)
    }

    @AssistedFactory
    interface Factory {
        fun create(source: LocalList): SelectionViewModel
    }
}
