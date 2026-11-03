package keinabersmart.kleinaberlaut.wear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.material3.MaterialTheme
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.SongOfflineState
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.download.WatchStorageRepository
import keinabersmart.kleinaberlaut.wear.sync.PhoneRemote
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Ein Eintrag einer Liste von Alben oder Playlists. [artist] ist beim Album der Künstler; ohne ihn steht die Zahl der Titel
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

@Composable
fun LocalAlbumsScreen(onClick: (Long) -> Unit, viewModel: LocalAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_albums, state, onClick)
}

@Composable
fun LocalPlaylistsScreen(onClick: (Long) -> Unit, viewModel: LocalPlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_playlists, state, onClick)
}

@Composable
fun RemoteAlbumsScreen(onClick: (Long) -> Unit, viewModel: RemoteAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_albums, state, onClick)
}

@Composable
fun RemotePlaylistsScreen(onClick: (Long) -> Unit, viewModel: RemotePlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_playlists, state, onClick)
}

@Composable
private fun EntryListContent(@StringRes titleRes: Int, state: EntryListState, onClick: (Long) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(stringResource(titleRes), textAlign = TextAlign.Center)
                }
            }
            when (state) {
                EntryListState.Loading -> item {
                    CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
                }
                is EntryListState.Message -> item {
                    Text(
                        stringResource(state.textRes),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is EntryListState.Ready -> items(state.entries, key = { it.key }) { entry ->
                    Button(
                        onClick = { onClick(entry.key) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            val loaded = entry.loadedCount
                            Text(
                                when {
                                    loaded != null && loaded < entry.songCount ->
                                        stringResource(R.string.selection_progress, loaded, entry.songCount)
                                    entry.artist != null -> entry.artist
                                    else -> pluralStringResource(R.plurals.song_count, entry.songCount, entry.songCount)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun EntryListPreview() {
    MaterialTheme {
        EntryListContent(
            R.string.list_albums,
            EntryListState.Ready(
                listOf(
                    ListEntry(1, "Mondlicht", 4, artist = "Nachtfalter", loadedCount = 4),
                    ListEntry(2, "Ein Album mit sehr langem Namen", 12, artist = "Jemand", loadedCount = 5),
                ),
            ),
            onClick = {},
        )
    }
}
