package keinabersmart.kleinaberlaut.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.download.DownloadRepository
import keinabersmart.kleinaberlaut.core.model.AlbumOfflineStatus
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.model.PlaylistDetails
import keinabersmart.kleinaberlaut.core.model.PlaylistEntry
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.NoSongActions
import keinabersmart.kleinaberlaut.ui.components.PreviewSong
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.ui.components.WatchStatusText
import keinabersmart.kleinaberlaut.ui.components.WatchToggleButton
import keinabersmart.kleinaberlaut.ui.components.formatDuration
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.library.DownloadButton
import keinabersmart.kleinaberlaut.ui.library.DownloadStatusLine
import keinabersmart.kleinaberlaut.ui.toLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel(assistedFactory = PlaylistViewModel.Factory::class)
class PlaylistViewModel @AssistedInject constructor(
    @Assisted private val playlistId: Long,
    private val playlistRepository: PlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val playbackConnection: PlaybackConnection,
    private val downloadRepository: DownloadRepository,
) : ViewModel(), SongActions {

    val playlist: StateFlow<LoadState<PlaylistDetails>> = playlistRepository.playlist(playlistId)
        .map { it.toLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val offline: StateFlow<AlbumOfflineStatus> = combine(
        playlistRepository.playlist(playlistId),
        downloadRepository.songStatuses(),
    ) { details, lookup -> lookup.statusOf(details?.entries?.map { it.song.id }.orEmpty()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumOfflineStatus.EMPTY)

    fun downloadPlaylist() {
        viewModelScope.launch { downloadRepository.downloadPlaylist(playlistId) }
    }

    fun removePlaylistDownload() {
        viewModelScope.launch { downloadRepository.removePlaylist(playlistId) }
    }

    fun play(startIndex: Int = 0) {
        songs()?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffle() {
        songs()?.let { playbackConnection.play(it, shuffle = true) }
    }

    fun rename(name: String) {
        viewModelScope.launch { playlistRepository.rename(playlistId, name) }
    }

    /** [onDeleted] erst danach – Zurücknavigieren beendet den `viewModelScope`. */
    fun delete(onDeleted: () -> Unit) {
        viewModelScope.launch {
            playlistRepository.delete(playlistId)
            onDeleted()
        }
    }

    fun removeEntry(entryId: Long) {
        viewModelScope.launch { playlistRepository.removeEntry(playlistId, entryId) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private fun songs(): List<Song>? = (playlist.value as? LoadState.Ready)?.value?.entries?.map { it.song }

    @AssistedFactory
    interface Factory {
        fun create(playlistId: Long): PlaylistViewModel
    }
}

@Composable
fun PlaylistScreen(
    playlistId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlaylistViewModel = hiltViewModel<PlaylistViewModel, PlaylistViewModel.Factory> { it.create(playlistId) },
) {
    val playlist by viewModel.playlist.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    PlaylistContent(
        state = playlist,
        offline = offline,
        onDownload = viewModel::downloadPlaylist,
        onRemoveDownload = viewModel::removePlaylistDownload,
        onBack = onBack,
        onPlay = viewModel::play,
        onShuffle = viewModel::shuffle,
        onRename = viewModel::rename,
        onDelete = { viewModel.delete(onDeleted = onBack) },
        onRemoveEntry = viewModel::removeEntry,
        actions = viewModel,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaylistContent(
    state: LoadState<PlaylistDetails>,
    offline: AlbumOfflineStatus,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
    onRemoveEntry: (Long) -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                if (state is LoadState.Ready) {
                    Column {
                        Text(state.value.playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        WatchStatusText(WearSelectionKind.PLAYLIST, state.value.playlist.id)
                    }
                }
            },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (state is LoadState.Ready) {
                    WatchToggleButton(WearSelectionKind.PLAYLIST, state.value.playlist.id)
                    PlaylistMenu(state.value.playlist, onRename, onDelete)
                }
            },
        )
        when (state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            is LoadState.Ready -> PlaylistEntries(state.value, offline, onPlay, onShuffle, onDownload, onRemoveDownload, onRemoveEntry, actions)
        }
    }
}

@Composable
private fun PlaylistEntries(
    details: PlaylistDetails,
    offline: AlbumOfflineStatus,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onRemoveEntry: (Long) -> Unit,
    actions: SongActions,
) {
    val playlist = details.playlist
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                val meta = listOf(
                    pluralStringResource(R.plurals.song_count, playlist.songCount, playlist.songCount),
                    formatDuration(playlist.durationMs),
                    playlistLocation(playlist.serverName),
                ).joinToString(" · ")
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
                if (details.entries.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { onPlay(0) }) {
                            Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_play))
                        }
                        FilledTonalButton(onClick = onShuffle) {
                            Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.action_shuffle))
                        }
                        // Rein lokale Playlists brauchen keinen Download.
                        if (offline.downloadableCount > 0) {
                            DownloadButton(
                                removeTitle = stringResource(R.string.download_album_remove_title),
                                removeText = stringResource(R.string.download_playlist_remove_text, playlist.name),
                                offline = offline,
                                onDownload = onDownload,
                                onRemoveDownload = onRemoveDownload,
                            )
                        }
                    }
                    DownloadStatusLine(offline)
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (details.entries.isEmpty()) {
            item(key = "empty") { EmptyHint(stringResource(R.string.playlist_empty)) }
        }
        // Ein Song kann mehrfach vorkommen – Schlüssel ist daher der Eintrag, nicht der Song.
        val serverName = playlist.serverName
        itemsIndexed(details.entries, key = { _, entry -> entry.entryId }) { index, entry ->
            SongListItem(
                song = entry.song,
                onClick = { onPlay(index) },
                actions = actions,
                onRemoveFromPlaylist = { onRemoveEntry(entry.entryId) },
                warning = if (entry.missingOnServer && serverName != null) {
                    stringResource(R.string.playlist_missing_on_server, serverName)
                } else {
                    null
                },
            )
        }
    }
}

@Composable
private fun PlaylistMenu(playlist: Playlist, onRename: (String) -> Unit, onDelete: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_rename)) },
                onClick = {
                    menuOpen = false
                    renaming = true
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.playlist_delete)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                onClick = {
                    menuOpen = false
                    confirmDelete = true
                },
            )
        }
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.playlist_save),
            initialName = playlist.name,
            onConfirm = { name, _ ->
                renaming = false
                onRename(name)
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete_title)) },
            text = {
                val server = playlist.serverName
                Text(
                    if (server != null) {
                        stringResource(R.string.playlist_delete_text_server, playlist.name, server)
                    } else {
                        stringResource(R.string.playlist_delete_text, playlist.name)
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.playlist_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun PlaylistContentPreview() {
    val entries = List(3) { PlaylistEntry(it.toLong(), PreviewSong.copy(id = it.toLong()), missingOnServer = it == 2) }
    KleinaberlautTheme {
        PlaylistContent(
            state = LoadState.Ready(
                PlaylistDetails(Playlist(1, "Sommer", 3, 645_000, serverName = "Navidrome"), entries),
            ),
            offline = AlbumOfflineStatus.EMPTY,
            onDownload = {},
            onRemoveDownload = {},
            onBack = {},
            onPlay = {},
            onShuffle = {},
            onRename = {},
            onDelete = {},
            onRemoveEntry = {},
            actions = NoSongActions,
        )
    }
}
