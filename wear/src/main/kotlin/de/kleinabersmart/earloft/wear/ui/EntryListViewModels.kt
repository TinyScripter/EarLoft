package de.kleinabersmart.earloft.wear.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.DownloadRepository
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.SongOfflineState
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.core.wearsync.WearRemotePodcastDto
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.download.WatchStorageRepository
import de.kleinabersmart.earloft.wear.sync.PhoneRemote
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ein Eintrag einer Liste von Alben, Künstlern, Playlists, Abos, Folgen oder Büchern. [artist] ist beim Album der Künstler,
 * bei Folgen und Büchern Podcast bzw. Autor; ohne ihn steht [albumCount] (Künstler) oder die Zahl der Titel da (bei
 * [songCount] 0 nichts), bei teilweise geladenen Listen der Stand ([loadedCount] von [songCount]). Nicht [enabled]: noch nicht
 * geladen. [started]: angefangen (Folge, Buch). [cover]: Vorschaubild, ohne es keins.
 */
data class ListEntry(
    val key: Long,
    val title: String,
    val songCount: Int,
    val artist: String? = null,
    val loadedCount: Int? = null,
    val enabled: Boolean = true,
    val albumCount: Int? = null,
    val cover: CoverRef? = null,
    val started: Boolean = false,
)

sealed interface EntryListState {
    data object Loading : EntryListState

    /** Nichts zu zeigen: leer, oder das Handy hat (noch) nichts geschrieben. */
    data class Message(@StringRes val textRes: Int) : EntryListState

    data class Ready(val entries: List<ListEntry>) : EntryListState
}

private const val STATE_SUBSCRIPTION_MS = 5_000L

/** Die Quelle (meist Room) hat eine Ausnahme geworfen ([orOnError]). */
private val LoadFailed = EntryListState.Message(R.string.list_load_failed)

/** Ein Album mit mindestens einem geladenen Titel auf der Uhr. */
private data class LoadedAlbum(val album: Album, val loadedCount: Int)

/** Die auf der Uhr geladenen Alben (mindestens ein Titel heruntergeladen), sortiert wie die Bibliothek. */
private fun loadedAlbums(library: LibraryRepository, downloads: DownloadRepository): Flow<List<LoadedAlbum>> =
    combine(library.albums(), library.songs(), downloads.songStatuses()) { albums, songs, offline ->
        val loadedPerAlbum = songs.filter { offline[it.id].state == SongOfflineState.DOWNLOADED }.groupingBy { it.albumId }.eachCount()
        albums.mapNotNull { album -> loadedPerAlbum[album.id]?.let { LoadedAlbum(album, it) } }
    }

/** Die auf der Uhr geladenen Alben, mit [artistId] nur die dieses Künstlers. */
@HiltViewModel(assistedFactory = LocalAlbumsViewModel.Factory::class)
class LocalAlbumsViewModel @AssistedInject constructor(
    @Assisted artistId: Long?,
    library: LibraryRepository,
    downloads: DownloadRepository,
) : ViewModel() {
    private val ofArtist: Flow<Set<Long>?> =
        if (artistId == null) flowOf(null) else library.albumIdsByArtist().map { it[artistId].orEmpty() }

    val state: StateFlow<EntryListState> = combine(loadedAlbums(library, downloads), ofArtist) { loaded, albumIds ->
        val entries = loaded.filter { albumIds == null || it.album.id in albumIds }.map { (album, loadedCount) ->
            ListEntry(album.id, album.title, album.songCount, artist = album.artist, loadedCount = loadedCount, cover = CoverRef.Album(album.id))
        }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    @AssistedFactory
    interface Factory {
        fun create(artistId: Long?): LocalAlbumsViewModel
    }
}

/** Die Künstler der geladenen Alben; als Bild das Cover ihres ersten Albums (Künstlerbilder gibt es auf der Uhr nicht). */
@HiltViewModel
class LocalArtistsViewModel @Inject constructor(
    library: LibraryRepository,
    downloads: DownloadRepository,
) : ViewModel() {
    val state: StateFlow<EntryListState> = combine(
        library.artists(),
        library.albumIdsByArtist(),
        loadedAlbums(library, downloads),
    ) { artists, albumIdsByArtist, loaded ->
        val entries = artists.mapNotNull { artist ->
            val albumIds = albumIdsByArtist[artist.id].orEmpty()
            val albums = loaded.filter { it.album.id in albumIds }
            val first = albums.firstOrNull() ?: return@mapNotNull null
            ListEntry(artist.id, artist.name, songCount = 0, albumCount = albums.size, cover = CoverRef.Album(first.album.id))
        }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/** Die Playlists der Auswahl vom Handy (Favoriten und Alben haben eigene Einträge). */
@HiltViewModel
class LocalPlaylistsViewModel @Inject constructor(storage: WatchStorageRepository) : ViewModel() {
    val state: StateFlow<EntryListState> = storage.selections().map { all ->
        val entries = all.filter { it.kind == WearSelectionKind.PLAYLIST }
            .map { ListEntry(it.playlistId, it.title, it.songCount, loadedCount = it.downloadedCount) }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/** Alle Alben des Handys (Fernsteuerung), mit [artistId] nur die dieses Künstlers; mit Vorschaubild vom Handy. */
@HiltViewModel(assistedFactory = RemoteAlbumsViewModel.Factory::class)
class RemoteAlbumsViewModel @AssistedInject constructor(
    @Assisted artistId: Long?,
    remote: PhoneRemote,
) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        val albumIds = artistId?.let { id -> library?.artists?.firstOrNull { it.id == id }?.albumIds?.toSet().orEmpty() }
        val albums = library?.albums.orEmpty().filter { albumIds == null || it.id in albumIds }
        when {
            library == null -> EntryListState.Message(R.string.remote_no_library)
            albums.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(
                albums.map { ListEntry(it.id, it.title, it.songCount, artist = it.artist, cover = CoverRef.Phone(WearThumbnailFile.Ref.Album(it.id))) },
            )
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    @AssistedFactory
    interface Factory {
        fun create(artistId: Long?): RemoteAlbumsViewModel
    }
}

/** Die Künstler des Handys; als Bild das Cover ihres ersten Albums. */
@HiltViewModel
class RemoteArtistsViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        val artists = library?.artists.orEmpty().filter { it.albumIds.isNotEmpty() }
        when {
            library == null -> EntryListState.Message(R.string.remote_no_library)
            artists.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(
                artists.map {
                    val cover = CoverRef.Phone(WearThumbnailFile.Ref.Album(it.albumIds.first()))
                    ListEntry(it.id, it.name, songCount = 0, albumCount = it.albumIds.size, cover = cover)
                },
            )
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/**
 * Podcasts bzw. Bücher auf der Uhr (Wear-Konzept 16.1/16.2): die Folgen bzw. offenen Bücher vom Handy in der Reihenfolge der
 * Warteschlange (wie am Handy gewählt, Gestartetes vorn), mit Bild; noch nicht vollständig geladene sind ausgegraut. Ein Tipp
 * spielt auf der Uhr.
 */
@HiltViewModel(assistedFactory = LocalSpokenViewModel.Factory::class)
class LocalSpokenViewModel @AssistedInject constructor(
    @Assisted kind: BookKind,
    continueListening: ContinueListening,
    podcasts: PodcastRepository,
    books: BookRepository,
    queue: EpisodeQueue,
    private val playback: PlaybackConnection,
) : ViewModel() {
    private val items: Flow<List<BookWithProgress>> = when (kind) {
        BookKind.EPISODE -> continueListening.episodes
        BookKind.BOOK -> books.books().map { all -> all.filter { it.progress?.finished != true } }
    }

    val state: StateFlow<EntryListState> = combine(
        items,
        queue.order,
        podcasts.downloadedIds(),
        books.downloads(),
    ) { items, order, downloadedEpisodes, bookDownloads ->
        val rank = order.withIndex().associate { it.value to it.index }
        val sorted = items.distinctBy { it.book.id }.sortedBy { rank[it.book.id] ?: Int.MAX_VALUE }
        if (sorted.isEmpty()) {
            EntryListState.Message(if (kind == BookKind.EPISODE) R.string.list_podcasts_empty_watch else R.string.list_books_empty_watch)
        } else {
            EntryListState.Ready(
                sorted.map {
                    val id = it.book.id
                    val loaded = id in downloadedEpisodes || bookDownloads[id]?.state == TrackDownload.DONE
                    ListEntry(id, it.book.title, songCount = 0, artist = it.book.author, enabled = loaded, cover = CoverRef.Book(id))
                },
            )
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    /** @return `false`, wenn sich die Folge bzw. das Buch nicht laden ließ (z. B. Datei inzwischen weg). */
    suspend fun play(bookId: Long): Boolean = playback.playBook(bookId) != null

    @AssistedFactory
    interface Factory {
        fun create(kind: BookKind): LocalSpokenViewModel
    }
}

/** Ein Buch bzw. eine Folge des Handys als Listeneintrag; [ListEntry.key] ist die Buch-ID am Handy. */
private fun WearRemoteBookDto.toEntry() = ListEntry(
    key = id,
    title = title,
    songCount = 0,
    artist = author.ifBlank { null },
    started = started,
    cover = CoverRef.Phone(WearThumbnailFile.Ref.Book(serverId, remoteId)),
)

/** Die Abos des Handys (Wear-Konzept 17); [ListEntry.key] ist die Position, [feedAt] liefert das Abo dazu. */
@HiltViewModel
class RemotePodcastsViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    private val podcasts = remote.library.map { it?.podcasts }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), null)

    val state: StateFlow<EntryListState> = podcasts.map { feeds ->
        when {
            feeds == null -> EntryListState.Message(R.string.remote_no_library)
            feeds.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(
                feeds.mapIndexed { index, feed ->
                    ListEntry(index.toLong(), feed.title, songCount = 0, artist = feed.author.ifBlank { null }, cover = CoverRef.Phone(WearThumbnailFile.Ref.Feed(feed.feedId)))
                },
            )
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    fun feedAt(key: Long): WearRemotePodcastDto? = podcasts.value?.getOrNull(key.toInt())
}

/** Die Hörbücher des Handys, angefangene zuerst (Wear-Konzept 17). */
@HiltViewModel
class RemoteBooksViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    private val books = remote.library.map { it?.books }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), null)

    val state: StateFlow<EntryListState> = books.map { list ->
        when {
            list == null -> EntryListState.Message(R.string.remote_no_library)
            list.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(list.map { it.toEntry() })
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    fun bookOf(key: Long): WearRemoteBookDto? = books.value?.firstOrNull { it.id == key }
}

/** Die Folgen eines Abos am Handy, einmal beim Öffnen abgefragt: angefangene zuerst, dann die neuesten (Wear-Konzept 17). */
@HiltViewModel(assistedFactory = RemoteEpisodesViewModel.Factory::class)
class RemoteEpisodesViewModel @AssistedInject constructor(
    @Assisted feedId: String,
    remote: PhoneRemote,
) : ViewModel() {
    private val _state = MutableStateFlow<EntryListState>(EntryListState.Loading)
    val state: StateFlow<EntryListState> = _state

    private var episodes: List<WearRemoteBookDto> = emptyList()

    init {
        viewModelScope.launch {
            val result = remote.episodes(feedId)
            _state.value = when {
                result == null -> EntryListState.Message(R.string.remote_unreachable)
                !result.found -> EntryListState.Message(R.string.remote_missing)
                result.episodes.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
                else -> {
                    episodes = result.episodes
                    EntryListState.Ready(result.episodes.map { it.toEntry() })
                }
            }
        }
    }

    fun episodeOf(key: Long): WearRemoteBookDto? = episodes.firstOrNull { it.id == key }

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): RemoteEpisodesViewModel
    }
}

/** Alle Playlists des Handys (Fernsteuerung). */
@HiltViewModel
class RemotePlaylistsViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        when {
            library == null -> EntryListState.Message(R.string.remote_no_library)
            library.playlists.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(library.playlists.map { ListEntry(it.id, it.name, it.songCount) })
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}
