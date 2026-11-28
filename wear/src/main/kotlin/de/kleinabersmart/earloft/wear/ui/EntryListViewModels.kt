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
import de.kleinabersmart.earloft.wear.sync.PhoneConnection
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
 * An entry in a list of albums, artists, playlists, subscriptions, episodes or books. [artist] is the artist for an album,
 * the podcast or author for episodes and books; without it [albumCount] (artists) or the number of tracks is shown (nothing for
 * [songCount] 0), for partially downloaded lists the progress ([loadedCount] of [songCount]). Not [enabled]: not yet
 * downloaded. [started]: started (episode, book). [cover]: thumbnail, none without it.
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
    /** Instead of [title]: a fixed entry with its own name (e.g. the queue). */
    @param:StringRes val titleRes: Int? = null,
)

sealed interface EntryListState {
    data object Loading : EntryListState

    /** Nothing to show: empty, or the phone has not written anything (yet). */
    data class Message(@StringRes val textRes: Int) : EntryListState

    data class Ready(val entries: List<ListEntry>) : EntryListState
}

private const val STATE_SUBSCRIPTION_MS = 5_000L

/** The source (usually Room) threw an exception ([orOnError]). */
private val LoadFailed = EntryListState.Message(R.string.list_load_failed)

/** An album with at least one downloaded track on the watch. */
private data class LoadedAlbum(val album: Album, val loadedCount: Int)

/** The albums downloaded on the watch (at least one track downloaded), sorted like the library. */
private fun loadedAlbums(library: LibraryRepository, downloads: DownloadRepository): Flow<List<LoadedAlbum>> =
    combine(library.albums(), library.songs(), downloads.songStatuses()) { albums, songs, offline ->
        val loadedPerAlbum = songs.filter { offline[it.id].state == SongOfflineState.DOWNLOADED }.groupingBy { it.albumId }.eachCount()
        albums.mapNotNull { album -> loadedPerAlbum[album.id]?.let { LoadedAlbum(album, it) } }
    }

/** The albums downloaded on the watch, with [artistId] only those of this artist. */
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

/** The artists of the downloaded albums; as image the cover of their first album (there are no artist images on the watch). */
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

/** The playlists of the selection from the phone (favorites and albums have their own entries). */
@HiltViewModel
class LocalPlaylistsViewModel @Inject constructor(storage: WatchStorageRepository) : ViewModel() {
    val state: StateFlow<EntryListState> = storage.selections().map { all ->
        val entries = all.filter { it.kind == WearSelectionKind.PLAYLIST }
            // Complete means all entries, also with a song twice in the playlist (review2 J88).
            .map { ListEntry(it.playlistId, it.title, it.songCount, loadedCount = if (it.downloadedCount >= it.distinctSongs) it.songCount else it.downloadedCount) }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/**
 * All albums of the phone (remote control), with [artistId] only those of this artist, with [favorites] only the favorited ones (most recently
 * favorited first); with thumbnail from the phone.
 */
@HiltViewModel(assistedFactory = RemoteAlbumsViewModel.Factory::class)
class RemoteAlbumsViewModel @AssistedInject constructor(
    @Assisted artistId: Long?,
    @Assisted favorites: Boolean,
    remote: PhoneRemote,
) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        val albumIds = artistId?.let { id -> library?.artists?.firstOrNull { it.id == id }?.albumIds?.toSet().orEmpty() }
        val all = library?.albums.orEmpty().filter { albumIds == null || it.id in albumIds }
        val albums = if (favorites) all.inOrderOf(library?.favoriteAlbumIds.orEmpty()) { it.id } else all
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
        fun create(artistId: Long?, favorites: Boolean): RemoteAlbumsViewModel
    }
}

/** The phone's artists, with [favorites] only the favorited ones (most recently favorited first); as image the cover of their first album. */
@HiltViewModel(assistedFactory = RemoteArtistsViewModel.Factory::class)
class RemoteArtistsViewModel @AssistedInject constructor(@Assisted favorites: Boolean, remote: PhoneRemote) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        val all = library?.artists.orEmpty().filter { it.albumIds.isNotEmpty() }
        val artists = if (favorites) all.inOrderOf(library?.favoriteArtistIds.orEmpty()) { it.id } else all
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

    @AssistedFactory
    interface Factory {
        fun create(favorites: Boolean): RemoteArtistsViewModel
    }
}

/** The entries with the identifiers [ids] in their order; unknown identifiers are dropped. */
private fun <T> List<T>.inOrderOf(ids: List<Long>, id: (T) -> Long): List<T> {
    val byId = associateBy(id)
    return ids.mapNotNull { byId[it] }
}

/**
 * Podcasts or books on the watch (Wear concept 16.1/16.2): the episodes or open books from the phone in the order of the
 * queue (as chosen on the phone, started ones first), with image; those not yet fully downloaded are grayed out. A tap
 * plays on the watch.
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

    /** @return `false` if the episode or book could not be loaded (e.g. file gone in the meantime). */
    suspend fun play(bookId: Long): Boolean = playback.playBook(bookId) != null

    @AssistedFactory
    interface Factory {
        fun create(kind: BookKind): LocalSpokenViewModel
    }
}

/** A book or episode of the phone as a list entry; [ListEntry.key] is the book ID on the phone. */
private fun WearRemoteBookDto.toEntry() = ListEntry(
    key = id,
    title = title,
    songCount = 0,
    artist = author.ifBlank { null },
    started = started,
    cover = CoverRef.Phone(WearThumbnailFile.Ref.Book(serverId, remoteId)),
)

/**
 * The podcast queue and below it the phone's subscriptions (Wear concept 17); [ListEntry.key] is the position,
 * [feedAt] returns the matching subscription, [QUEUE_KEY] the queue (always there, even if empty; not for older phones
 * that do not provide it).
 */
@HiltViewModel
class RemotePodcastsViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    private val library = remote.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), null)

    val state: StateFlow<EntryListState> = library.map { library ->
        val feeds = library?.podcasts
        when {
            feeds == null -> EntryListState.Message(R.string.remote_no_library)
            else -> EntryListState.Ready(
                listOfNotNull(ListEntry(QUEUE_KEY, title = "", songCount = 0, titleRes = R.string.list_queue).takeIf { library.hasPodcastQueue }) +
                    feeds.mapIndexed { index, feed ->
                        ListEntry(index.toLong(), feed.title, songCount = 0, artist = feed.author.ifBlank { null }, cover = CoverRef.Phone(WearThumbnailFile.Ref.Feed(feed.feedId)))
                    },
            )
        }
    }.orOnError(LoadFailed).stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)

    fun feedAt(key: Long): WearRemotePodcastDto? = library.value?.podcasts?.getOrNull(key.toInt())

    companion object {
        const val QUEUE_KEY = -1L
    }
}

/** The phone's audiobooks, started ones first (Wear concept 17). */
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

/** The episodes of a subscription on the phone, queried on opening (again once the phone is back): started ones first, then the newest (Wear concept 17). */
@HiltViewModel(assistedFactory = RemoteEpisodesViewModel.Factory::class)
class RemoteEpisodesViewModel @AssistedInject constructor(
    @Assisted feedId: String,
    remote: PhoneRemote,
    phoneConnection: PhoneConnection,
) : ViewModel() {
    private val _state = MutableStateFlow<EntryListState>(EntryListState.Loading)
    val state: StateFlow<EntryListState> = _state

    private var episodes: List<WearRemoteBookDto> = emptyList()

    init {
        viewModelScope.launch {
            val result = loadFromPhone(
                phoneIsThere = { phoneConnection.connectedPhoneNodeId(fresh = true) != null },
                onUnreachable = { _state.value = EntryListState.Message(R.string.remote_unreachable) },
            ) { remote.episodes(feedId) }
            _state.value = when {
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

/** All playlists of the phone (remote control). */
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
