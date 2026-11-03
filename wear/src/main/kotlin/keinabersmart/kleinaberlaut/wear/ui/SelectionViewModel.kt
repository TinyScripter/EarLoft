package keinabersmart.kleinaberlaut.wear.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.model.PlaylistDetails
import keinabersmart.kleinaberlaut.core.model.PlaylistEntry
import keinabersmart.kleinaberlaut.core.model.SongOfflineLookup
import keinabersmart.kleinaberlaut.core.model.SongOfflineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** `Loading` vor dem ersten Wert; `Missing`, wenn die Auswahl inzwischen entfernt wurde. */
sealed interface SelectionUiState {
    data object Loading : SelectionUiState

    data object Missing : SelectionUiState

    data class Ready(val details: PlaylistDetails, val offline: SongOfflineLookup) : SelectionUiState {
        val downloadedCount: Int get() = details.entries.map { it.song.id }.distinct().count { isLoaded(it) }

        fun isLoaded(songId: Long) = offline[songId].state == SongOfflineState.DOWNLOADED
    }
}

/** Woher die Titel einer Liste auf der Uhr kommen: eine Geräte-Playlist (Auswahl vom Handy) oder ein geladenes Album. */
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
                // Ein Album verhält sich wie eine Playlist ohne Duplikate; die Song-ID dient als Eintrags-ID.
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
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionUiState.Loading)

    /** Spielt die geladenen Songs der Auswahl; ab [entryId] (Duplikate in Playlists bleiben unterscheidbar) oder von vorn. */
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
