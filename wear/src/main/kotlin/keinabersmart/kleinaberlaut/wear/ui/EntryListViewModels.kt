package keinabersmart.kleinaberlaut.wear.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.SongOfflineState
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.download.WatchStorageRepository
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Ein Eintrag einer Liste von Alben, Playlists oder Büchern. [artist] ist beim Album der Künstler; ohne ihn steht die Zahl der Titel
 * da, bei teilweise geladenen Listen der Stand ([loadedCount] von [songCount]).
 */
data class ListEntry(
    val key: Long,
    val title: String,
    val songCount: Int,
    val artist: String? = null,
    val loadedCount: Int? = null,
)

sealed interface EntryListState {
    data object Loading : EntryListState

    /** Nichts zu zeigen: leer, oder das Handy hat (noch) nichts geschrieben. */
    data class Message(@StringRes val textRes: Int) : EntryListState

    data class Ready(val entries: List<ListEntry>) : EntryListState
}

private const val STATE_SUBSCRIPTION_MS = 5_000L

/** Die auf der Uhr geladenen Alben: alle, von denen mindestens ein Titel heruntergeladen ist. */
@HiltViewModel
class LocalAlbumsViewModel @Inject constructor(
    library: LibraryRepository,
    downloads: DownloadRepository,
) : ViewModel() {
    val state: StateFlow<EntryListState> = combine(library.albums(), library.songs(), downloads.songStatuses()) { albums, songs, offline ->
        val loadedPerAlbum = songs.filter { offline[it.id].state == SongOfflineState.DOWNLOADED }.groupingBy { it.albumId }.eachCount()
        val entries = albums.filter { it.id in loadedPerAlbum }
            .map { ListEntry(it.id, it.title, it.songCount, artist = it.artist, loadedCount = loadedPerAlbum.getValue(it.id)) }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/** Die Playlists der Auswahl vom Handy (Favoriten und Alben haben eigene Einträge). */
@HiltViewModel
class LocalPlaylistsViewModel @Inject constructor(storage: WatchStorageRepository) : ViewModel() {
    val state: StateFlow<EntryListState> = storage.selections().map { all ->
        val entries = all.filter { it.kind == WearSelectionKind.PLAYLIST }
            .map { ListEntry(it.playlistId, it.title, it.songCount, loadedCount = it.downloadedCount) }
        if (entries.isEmpty()) EntryListState.Message(R.string.list_empty_watch) else EntryListState.Ready(entries)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/** Alle Alben des Handys (Fernsteuerung). */
@HiltViewModel
class RemoteAlbumsViewModel @Inject constructor(remote: PhoneRemote) : ViewModel() {
    val state: StateFlow<EntryListState> = remote.library.map { library ->
        when {
            library == null -> EntryListState.Message(R.string.remote_no_library)
            library.albums.isEmpty() -> EntryListState.Message(R.string.list_empty_phone)
            else -> EntryListState.Ready(library.albums.map { ListEntry(it.id, it.title, it.songCount, artist = it.artist) })
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}

/**
 * „Weiterhören“ des Handys (Fernsteuerung): angefangene Bücher und die Warteschlange der Podcasts, einmal beim Öffnen
 * abgefragt. [ListEntry.key] ist die Buch- bzw. Folgen-ID am Handy, [ListEntry.artist] Autor bzw. Podcast.
 */
@HiltViewModel
class RemoteContinueViewModel @Inject constructor(private val remote: PhoneRemote) : ViewModel() {
    private val _state = MutableStateFlow<EntryListState>(EntryListState.Loading)
    val state: StateFlow<EntryListState> = _state

    init {
        viewModelScope.launch {
            val result = remote.tracks(WearRemoteListKind.CONTINUE, null)
            _state.value = when {
                result == null -> EntryListState.Message(R.string.remote_unreachable)
                result.tracks.isEmpty() -> EntryListState.Message(R.string.list_continue_empty)
                else -> EntryListState.Ready(result.tracks.map { ListEntry(it.songId, it.title, songCount = 0, artist = it.artist) })
            }
        }
    }

    /** Das Handy startet das Buch bzw. die Folge an der gemerkten Stelle. @return `false`, wenn es nicht erreichbar war. */
    suspend fun play(bookId: Long): Boolean = remote.play(WearRemotePlayRequest(WearRemoteListKind.CONTINUE, refId = bookId))
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STATE_SUBSCRIPTION_MS), EntryListState.Loading)
}
