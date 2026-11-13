package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.data.repository.ServerRepository
import de.kleinabersmart.earloft.core.data.sync.LibrarySettings
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ArtistSort
import de.kleinabersmart.earloft.core.model.SongSort
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Decade
import de.kleinabersmart.earloft.core.model.Genre
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.ui.components.SongActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    serverRepository: ServerRepository,
    private val librarySettings: LibrarySettings,
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel(), SongActions {

    val albumSort: StateFlow<AlbumSort> = librarySettings.albumSort.stateInViewModel(AlbumSort.TITLE)
    val artistSort: StateFlow<ArtistSort> = librarySettings.artistSort.stateInViewModel(ArtistSort.NAME)
    val songSort: StateFlow<SongSort> = librarySettings.songSort.stateInViewModel(SongSort.TITLE)

    // Erst nach der gespeicherten Reihenfolge laden, sonst blitzt die Standard-Reihenfolge auf.
    val albums: StateFlow<LoadState<List<Album>>> = librarySettings.albumSort.distinctUntilChanged()
        .flatMapLatest { libraryRepository.albums(it) }.stateInViewModel()
    val artists: StateFlow<LoadState<List<Artist>>> = librarySettings.artistSort.distinctUntilChanged()
        .flatMapLatest { libraryRepository.artists(it) }.stateInViewModel()
    val songs: StateFlow<LoadState<List<Song>>> = librarySettings.songSort.distinctUntilChanged()
        .flatMapLatest { libraryRepository.songs(it) }.stateInViewModel()
    val genres: StateFlow<LoadState<List<Genre>>> = libraryRepository.genres().stateInViewModel()
    val decades: StateFlow<List<Decade>> = libraryRepository.decades().stateInViewModel(emptyList())
    val favoriteCount: StateFlow<Int> = favoritesRepository.favorites().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)
    val playlists: StateFlow<LoadState<List<Playlist>>> = playlistRepository.playlists().stateInViewModel()

    /** `null`, solange noch geladen wird; nur für die Auswahl beim Anlegen einer Playlist. */
    val servers: StateFlow<List<ServerInfo>?> = serverRepository.servers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    fun setAlbumSort(sort: AlbumSort) {
        viewModelScope.launch { librarySettings.setAlbumSort(sort) }
    }

    fun setArtistSort(sort: ArtistSort) {
        viewModelScope.launch { librarySettings.setArtistSort(sort) }
    }

    fun setSongSort(sort: SongSort) {
        viewModelScope.launch { librarySettings.setSongSort(sort) }
    }

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

    private fun <T> Flow<T>.stateInViewModel(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    private fun <T> Flow<List<T>>.stateInViewModel(): StateFlow<LoadState<List<T>>> =
        asLoadState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoadState.Loading)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
