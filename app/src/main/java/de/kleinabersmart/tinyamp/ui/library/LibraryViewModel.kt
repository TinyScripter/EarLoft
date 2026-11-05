package de.kleinabersmart.tinyamp.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.repository.FavoritesRepository
import de.kleinabersmart.tinyamp.core.data.repository.LibraryRepository
import de.kleinabersmart.tinyamp.core.data.repository.PlaylistRepository
import de.kleinabersmart.tinyamp.core.data.repository.ServerRepository
import de.kleinabersmart.tinyamp.core.model.Album
import de.kleinabersmart.tinyamp.core.model.Artist
import de.kleinabersmart.tinyamp.core.model.Playlist
import de.kleinabersmart.tinyamp.core.model.ServerInfo
import de.kleinabersmart.tinyamp.core.model.Song
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.asLoadState
import de.kleinabersmart.tinyamp.ui.valueOrNull
import de.kleinabersmart.tinyamp.ui.components.SongActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    serverRepository: ServerRepository,
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel(), SongActions {

    val albums: StateFlow<LoadState<List<Album>>> = libraryRepository.albums().stateInViewModel()
    val artists: StateFlow<LoadState<List<Artist>>> = libraryRepository.artists().stateInViewModel()
    val songs: StateFlow<LoadState<List<Song>>> = libraryRepository.songs().stateInViewModel()
    val favoriteCount: StateFlow<Int> = favoritesRepository.favorites().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)
    val playlists: StateFlow<LoadState<List<Playlist>>> = playlistRepository.playlists().stateInViewModel()

    /** `null`, solange noch geladen wird; nur für die Auswahl beim Anlegen einer Playlist. */
    val servers: StateFlow<List<ServerInfo>?> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun createPlaylist(name: String, serverSourceId: String?, onCreated: (Long) -> Unit) {
        viewModelScope.launch { onCreated(playlistRepository.create(name, serverSourceId)) }
    }

    fun playSongs(startIndex: Int) {
        songs.value.valueOrNull?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffleSongs() {
        songs.value.valueOrNull?.let { playbackConnection.play(it, shuffle = true) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private fun <T> Flow<List<T>>.stateInViewModel(): StateFlow<LoadState<List<T>>> =
        asLoadState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoadState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
