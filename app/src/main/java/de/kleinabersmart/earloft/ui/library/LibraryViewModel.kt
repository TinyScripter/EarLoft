package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.AlbumColorRepository
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the "Favorites" chip is; each tab remembers it for itself. */
enum class FavoritesChip { ALBUMS, GENRES, ARTISTS, SONGS }

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val libraryRepository: LibraryRepository,
    serverRepository: ServerRepository,
    private val librarySettings: LibrarySettings,
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
    private val albumColorRepository: AlbumColorRepository,
    private val savedStateHandle: SavedStateHandle,
) : ViewModel(), SongActions {

    val albumSort: StateFlow<AlbumSort> = librarySettings.albumSort.stateInViewModel(AlbumSort.TITLE)
    val artistSort: StateFlow<ArtistSort> = librarySettings.artistSort.stateInViewModel(ArtistSort.NAME)
    val songSort: StateFlow<SongSort> = librarySettings.songSort.stateInViewModel(SongSort.TITLE)

    // Load only after the stored order, otherwise the default order flashes up. By cover color, the missing colors are computed
    // while the albums are shown (the list re-sorts after each batch).
    val albums: StateFlow<LoadState<List<Album>>> = librarySettings.albumSort.distinctUntilChanged()
        .flatMapLatest { sort ->
            val albums = libraryRepository.albums(sort)
            if (sort != AlbumSort.COLOR) {
                albums
            } else {
                channelFlow {
                    launch { albumColorRepository.fillMissing() }
                    albums.collect { send(it) }
                }
            }
        }.stateInViewModel()
    val artists: StateFlow<LoadState<List<Artist>>> = librarySettings.artistSort.distinctUntilChanged()
        .flatMapLatest { libraryRepository.artists(it) }.stateInViewModel()
    val songs: StateFlow<LoadState<List<Song>>> = librarySettings.songSort.distinctUntilChanged()
        .flatMapLatest { libraryRepository.songs(it) }.stateInViewModel()
    val genres: StateFlow<LoadState<List<Genre>>> = libraryRepository.genres().stateInViewModel()
    /** `null` until loaded: until then the selected decades aren't known either ([selectedDecades]). */
    private val knownDecades: StateFlow<List<Decade>?> = libraryRepository.decades().stateInViewModel(null)
    val decades: StateFlow<List<Decade>> = knownDecades.map { it.orEmpty() }.stateInViewModel(emptyList())

    // Selected chips (on/off, several at once); none selected = all albums. Whatever no longer exists doesn't count anymore.
    // Kept in the saved state, so they survive the end of the process like the tab and the scroll position (review2 J76).
    private val chosenDecades = MutableStateFlow(savedStateHandle.get<IntArray>(KEY_DECADES)?.toSet().orEmpty())
    private val chosenGenres = MutableStateFlow(savedStateHandle.get<ArrayList<String>>(KEY_GENRES)?.toSet().orEmpty())

    /**
     * Favorite albums and artists, `null` until loaded (as [selectedDecades]): with the favorites chip on after the end of the process,
     * filtering against an empty set first flashed "No matches", and crate, carousel and grid lost their place (review5 P59).
     */
    private val loadedFavoriteAlbumIds: StateFlow<Set<Long>?> =
        favoritesRepository.favoriteAlbumIds().map<Set<Long>, Set<Long>?> { it }.stateInViewModel(null)
    private val loadedFavoriteArtistIds: StateFlow<Set<Long>?> =
        favoritesRepository.favoriteArtistIds().map<Set<Long>, Set<Long>?> { it }.stateInViewModel(null)
    val favoriteAlbumIds: StateFlow<Set<Long>> = loadedFavoriteAlbumIds.map { it.orEmpty() }.stateInViewModel(emptySet())
    val favoriteArtistIds: StateFlow<Set<Long>> = loadedFavoriteArtistIds.map { it.orEmpty() }.stateInViewModel(emptySet())

    private val chosenFavorites = MutableStateFlow<Set<FavoritesChip>>(
        savedStateHandle.get<ArrayList<String>>(KEY_FAVORITES).orEmpty().mapNotNullTo(HashSet()) { name ->
            FavoritesChip.entries.firstOrNull { it.name == name }
        },
    )

    init {
        viewModelScope.launch { chosenDecades.collect { savedStateHandle[KEY_DECADES] = it.toIntArray() } }
        viewModelScope.launch { chosenGenres.collect { savedStateHandle[KEY_GENRES] = ArrayList(it) } }
        viewModelScope.launch { chosenFavorites.collect { chips -> savedStateHandle[KEY_FAVORITES] = chips.mapTo(ArrayList()) { it.name } } }
    }

    /** Tabs in which the "Favorites" chip is on; it filters in addition to the other chips. */
    val favoritesOnly: StateFlow<Set<FavoritesChip>> = chosenFavorites.asStateFlow()

    /**
     * `null` until the decades are loaded. Starting with "none" would look like a new filter once they arrive, and crate and grid
     * would jump to their start after a process death with a decade chosen (review4 T57, J76).
     */
    val selectedDecades: StateFlow<Set<Int>?> = combine(chosenDecades, knownDecades) { chosen, all ->
        all?.let { chosen.intersect(it.mapTo(HashSet()) { decade -> decade.startYear }) }
    }.stateInViewModel(null)

    /** Selected genres as [Genre.key]; `null` until the genres are loaded (as [selectedDecades]). */
    val selectedGenres: StateFlow<Set<String>?> = combine(chosenGenres, genres) { chosen, all ->
        if (all is LoadState.Loading) null else chosen.intersect(all.valueOrNull.orEmpty().mapTo(HashSet()) { it.key })
    }.stateInViewModel(null)

    /** The albums (in their sort order) from the selected decades. */
    val albumsOfDecades: StateFlow<LoadState<List<Album>>> =
        combine(albums, selectedDecades.map { it.orEmpty() }, favoritesOnly, loadedFavoriteAlbumIds) { state, chosen, favoritesOnly, favorites ->
            if (FavoritesChip.ALBUMS in favoritesOnly && favorites == null) return@combine LoadState.Loading
            state.filterAlbums(chosen.isNotEmpty()) { album -> album.year?.takeIf { it >= 1000 }?.let { it / 10 * 10 in chosen } == true }
                .filterAlbums(FavoritesChip.ALBUMS in favoritesOnly) { it.id in favorites.orEmpty() }
        }.stateInViewModel(LoadState.Loading)

    /** The albums (in their sort order) with at least one song from the selected genres. */
    val albumsOfGenres: StateFlow<LoadState<List<Album>>> =
        combine(albums, selectedGenres.map { it.orEmpty() }, libraryRepository.albumGenreKeys(), favoritesOnly, loadedFavoriteAlbumIds) {
                state, chosen, genresOf, favoritesOnly, favorites ->
            if (FavoritesChip.GENRES in favoritesOnly && favorites == null) return@combine LoadState.Loading
            state.filterAlbums(chosen.isNotEmpty()) { album -> genresOf[album.id]?.any { it in chosen } == true }
                .filterAlbums(FavoritesChip.GENRES in favoritesOnly) { it.id in favorites.orEmpty() }
        }.stateInViewModel(LoadState.Loading)

    /** The artists (in their sort order), with the "Favorites" chip only the favorited ones. */
    val artistsShown: StateFlow<LoadState<List<Artist>>> =
        combine(artists, favoritesOnly, loadedFavoriteArtistIds) { state, favoritesOnly, favorites ->
            if (FavoritesChip.ARTISTS in favoritesOnly && favorites == null) {
                LoadState.Loading
            } else if (FavoritesChip.ARTISTS in favoritesOnly && state is LoadState.Ready) {
                LoadState.Ready(state.value.filter { it.id in favorites.orEmpty() })
            } else {
                state
            }
        }.stateInViewModel(LoadState.Loading)

    /** The songs (in their sort order), with the "Favorites" chip only the favorited ones. */
    val songsShown: StateFlow<LoadState<List<Song>>> =
        combine(songs, favoritesOnly) { state, favoritesOnly ->
            if (FavoritesChip.SONGS in favoritesOnly && state is LoadState.Ready) {
                LoadState.Ready(state.value.filter { it.isFavorite })
            } else {
                state
            }
        }.stateInViewModel(LoadState.Loading)

    fun toggleFavoritesOnly(chip: FavoritesChip) = chosenFavorites.update { if (chip in it) it - chip else it + chip }

    /** All songs of the shown [artists], artist by artist (each song once) or shuffled. */
    fun playArtists(artists: List<Artist>, shuffle: Boolean) {
        viewModelScope.launch {
            val songs = artists.flatMap { libraryRepository.songsOfArtist(it.id) }.distinctBy { it.id }
            if (songs.isNotEmpty()) playbackConnection.play(songs, shuffle = shuffle)
        }
    }

    /** All songs of the shown (filtered) [albums], album by album in their order or shuffled. */
    fun playAlbums(albums: List<Album>, shuffle: Boolean) {
        viewModelScope.launch {
            val songs = libraryRepository.songsOfAlbums(albums.map { it.id })
            if (songs.isNotEmpty()) playbackConnection.play(songs, shuffle = shuffle)
        }
    }

    fun toggleDecade(startYear: Int) = chosenDecades.update { if (startYear in it) it - startYear else it + startYear }

    fun toggleGenre(genre: Genre) = chosenGenres.update { if (genre.key in it) it - genre.key else it + genre.key }

    val favoriteCount: StateFlow<Int> = favoritesRepository.favorites().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)
    val playlists: StateFlow<LoadState<List<Playlist>>> = playlistRepository.playlists().stateInViewModel()

    /** `null` while still loading; only for the selection when creating a playlist, so only enabled ones (review G64). */
    val servers: StateFlow<List<ServerInfo>?> = serverRepository.servers().map { all -> all.filter { it.enabled } }
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
        songsShown.value.valueOrNull?.takeIf { it.isNotEmpty() }?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffleSongs() {
        songsShown.value.valueOrNull?.takeIf { it.isNotEmpty() }?.let { playbackConnection.play(it, shuffle = true) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private fun LoadState<List<Album>>.filterAlbums(active: Boolean, keep: (Album) -> Boolean): LoadState<List<Album>> =
        if (active && this is LoadState.Ready) LoadState.Ready(value.filter(keep)) else this

    private fun <T> Flow<T>.stateInViewModel(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), initial)

    private fun <T> Flow<List<T>>.stateInViewModel(): StateFlow<LoadState<List<T>>> =
        asLoadState().stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoadState.Loading)

    private companion object {
        const val KEY_DECADES = "decades"
        const val KEY_GENRES = "genres"
        const val KEY_FAVORITES = "favorites"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
