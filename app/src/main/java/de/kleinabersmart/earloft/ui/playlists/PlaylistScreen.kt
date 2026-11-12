package de.kleinabersmart.earloft.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.PlaylistDetails
import de.kleinabersmart.earloft.core.model.PlaylistEntry
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.groupedItemsIndexed
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.NoSongActions
import de.kleinabersmart.earloft.ui.components.PreviewSong
import de.kleinabersmart.earloft.ui.components.SongActions
import de.kleinabersmart.earloft.ui.components.SongListItem
import de.kleinabersmart.earloft.ui.components.WatchStatusText
import de.kleinabersmart.earloft.ui.components.WatchToggleButton
import de.kleinabersmart.earloft.ui.components.formatDuration
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.library.DownloadButton
import de.kleinabersmart.earloft.ui.library.DownloadStatusLine
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

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
        onReorder = viewModel::reorder,
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
    onReorder: (entryIds: List<Long>) -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
) {
    // Beim Umsortieren eine Arbeitskopie; erst „Speichern“ übernimmt sie, Zurück verwirft sie.
    var order by remember { mutableStateOf<SnapshotStateList<PlaylistEntry>?>(null) }
    val editing = order
    BackHandler(enabled = editing != null) { order = null }
    Column(modifier.fillMaxSize()) {
        if (editing != null) {
            TopAppBar(
                title = { Text(stringResource(R.string.playlist_reorder)) },
                navigationIcon = {
                    IconButton(onClick = { order = null }) {
                        Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        onReorder(editing.map { it.entryId })
                        order = null
                    }) { Text(stringResource(R.string.playlist_save)) }
                },
            )
            PlaylistOrderList(editing)
            return@Column
        }
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
                    val entries = state.value.entries
                    PlaylistMenu(
                        playlist = state.value.playlist,
                        onRename = onRename,
                        onDelete = onDelete,
                        onReorder = if (entries.size > 1) ({ order = entries.toMutableStateList() }) else null,
                    )
                }
            },
        )
        when (state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
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
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") {
            Column(Modifier.fillMaxWidth().padding(horizontal = GroupMargin + 16.dp)) {
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
        groupedItemsIndexed(details.entries, key = { _, entry -> entry.entryId }) { index, entry ->
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
private fun PlaylistMenu(playlist: Playlist, onRename: (String) -> Unit, onDelete: () -> Unit, onReorder: (() -> Unit)?) {
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { menuOpen = true }) {
            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            if (onReorder != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.playlist_reorder)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_drag_handle), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        onReorder()
                    },
                )
            }
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
    EarLoftTheme {
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
            onReorder = {},
            actions = NoSongActions,
        )
    }
}
