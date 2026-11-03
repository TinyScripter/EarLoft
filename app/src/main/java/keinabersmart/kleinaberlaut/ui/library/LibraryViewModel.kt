package keinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.data.repository.ServerRepository
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
import keinabersmart.kleinaberlaut.ui.valueOrNull
import keinabersmart.kleinaberlaut.ui.components.SongActions
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
