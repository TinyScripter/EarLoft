package de.kleinabersmart.earloft.ui.player

import de.kleinabersmart.earloft.ui.components.formatDuration
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.components.LocalLibraryLinks
import de.kleinabersmart.earloft.ui.components.ArtistPicker
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.QueueMoreMenu
import de.kleinabersmart.earloft.ui.components.QueueTitle
import de.kleinabersmart.earloft.ui.components.rememberReorderState
import de.kleinabersmart.earloft.ui.components.reorderActions
import de.kleinabersmart.earloft.ui.components.reorderHandle
import de.kleinabersmart.earloft.ui.components.reorderableItem
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.playlists.AddToPlaylistDialog
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val laterMs by viewModel.queueLaterMs.collectAsStateWithLifecycle()
    QueueContent(
        state = state,
        // Count and time left, as in the podcast and book queues: the rest of the current song plus everything after it.
        summary = laterMs?.let { later ->
            val left = later + (state.durationMs - viewModel.currentPositionMs).coerceAtLeast(0)
            val count = state.queue.size
            "${pluralStringResource(R.plurals.song_count, count, count)} · ${stringResource(R.string.book_remaining, formatDuration(left))}"
        },
        onBack = onBack,
        onItemClick = viewModel::skipToQueueItem,
        onRemove = viewModel::removeQueueItem,
        onMove = viewModel::moveQueueItem,
        onClear = viewModel::clearQueue,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueContent(
    state: PlayerState,
    summary: String?,
    onBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var saving by remember { mutableStateOf(false) }
    if (saving) AddToPlaylistDialog(songIds = state.queue.map { it.songId }, onDismiss = { saving = false })
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { QueueTitle(summary = summary.takeIf { state.queue.isNotEmpty() }) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                QueueMoreMenu(
                    clearMessage = stringResource(R.string.music_queue_clear_message),
                    // The current title stays; there's only something to clear once more is listed.
                    canClear = state.queue.size > 1,
                    onClear = onClear,
                ) { close ->
                    // New or existing playlist; the same selection as "Add to playlist" for songs and albums.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.queue_save_as_playlist)) },
                        enabled = state.queue.isNotEmpty(),
                        onClick = {
                            close()
                            saving = true
                        },
                    )
                }
            },
        )
        QueueList(
            state = state,
            onItemClick = onItemClick,
            onRemove = onRemove,
            onMove = onMove,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

/** The queue with drag handles; also to the right of playback on wide windows. */
@Composable
fun QueueList(
    state: PlayerState,
    onItemClick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        if (state.queue.isEmpty()) {
            EmptyHint(stringResource(R.string.player_nothing_playing))
            return@Box
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))

        // Entries can occur twice, so each row gets its own ID as key.
        // The local copy is reordered immediately while dragging; the player follows afterwards.
        var nextRowId by remember { mutableIntStateOf(0) }
        val rows = remember { mutableStateListOf<QueueRow>() }
        var currentIndex by remember { mutableIntStateOf(state.currentIndex) }
        val reorder = rememberReorderState(listState)
        val latest by rememberUpdatedState(state)
        fun syncRows() {
            currentIndex = latest.currentIndex
            // Already reordered (drag, "Move up/down"): keep the IDs, otherwise nothing glides to its place.
            if (rows.map { it.entry } == latest.queue) return
            rows.clear()
            latest.queue.forEach { rows.add(QueueRow(nextRowId++, it)) }
        }
        LaunchedEffect(state.queue, state.currentIndex) {
            if (reorder.draggedKey == null) syncRows()
        }
        val moveRow = { from: Int, to: Int ->
            rows.add(to, rows.removeAt(from))
            currentIndex = when {
                currentIndex == from -> to
                from < currentIndex && currentIndex <= to -> currentIndex - 1
                to <= currentIndex && currentIndex < from -> currentIndex + 1
                else -> currentIndex
            }
            onMove(from, to)
        }

        LazyColumn(state = listState) {
            items(rows, key = { it.id }) { row ->
                val index = rows.indexOf(row)
                val entry = row.entry
                val isCurrent = index == currentIndex
                var menu by remember { mutableStateOf(false) }
                // The item modifier (drag offset, animation) belongs to the outermost layout of the item.
                Box(Modifier.reorderableItem(this, reorder, row.id)) {
                    if (menu) QueueEntryMenu(entry, onDismiss = { menu = false })
                    ListItem(
                        headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    painterResource(DesignR.drawable.ic_drag_handle),
                                    contentDescription = stringResource(R.string.action_reorder_queue_item),
                                    modifier = Modifier
                                        .size(40.dp)
                                        .padding(8.dp)
                                        .reorderHandle(reorder, row.id, index = { rows.indexOfFirst { it.id == row.id } }, onMove = moveRow,
                                        // Without a swap no new state follows; whatever arrived during the drag applies now.
                                        onDragEnd = { moved -> if (!moved) syncRows() }),
                                )
                                AlbumArt(
                                    albumId = entry.albumId,
                                    modifier = Modifier.padding(start = 4.dp).size(48.dp),
                                    shape = MaterialTheme.shapes.small,
                                )
                            }
                        },
                        trailingContent = {
                            IconButton(onClick = { onRemove(rows.indexOf(row)) }) {
                                Icon(
                                    painterResource(DesignR.drawable.ic_close),
                                    contentDescription = stringResource(R.string.action_remove_from_queue),
                                )
                            }
                        },
                        colors = if (isCurrent) {
                            ListItemDefaults.colors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        } else {
                            ListItemDefaults.colors()
                        },
                        modifier = Modifier
                            // Long press: the song's menu, like the rows of the podcast and book queues.
                            .combinedClickable(
                                onClick = { onItemClick(rows.indexOf(row)) },
                                onLongClickLabel = stringResource(R.string.action_more),
                                onLongClick = { menu = true },
                            )
                            .reorderActions(index, rows.size, moveRow),
                    )
                }
            }
        }
        // Without a label: the order is the playback order. The drag handles for reordering are on the left.
        LazyListFastScroller(listState)
    }
}

private data class QueueRow(val id: Int, val entry: QueueEntry)

/** Menu of a queue entry (long press): go to album or artist, add to a playlist, share. */
@Composable
private fun QueueEntryMenu(entry: QueueEntry, onDismiss: () -> Unit) {
    val links = LocalLibraryLinks.current
    var menuOpen by remember { mutableStateOf(true) }
    var addToPlaylist by remember { mutableStateOf(false) }
    ArtistPicker(
        lookup = { links?.artistsOfSong?.invoke(entry.songId, entry.albumId).orEmpty() },
        onArtistClick = {
            onDismiss()
            links?.openArtist?.invoke(it)
        },
        // The menu is already closed: without this the row would keep it "open" and ignore the next long press (review H17).
        onCancel = onDismiss,
    ) { openArtist ->
        DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
            DropdownMenuItem(
                text = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {},
                enabled = false,
                colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
            )
            HorizontalDivider()
            if (links != null) {
                entry.albumId?.let { albumId ->
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_go_to_album)) },
                        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_album), contentDescription = null) },
                        onClick = {
                            onDismiss()
                            links.openAlbum(albumId)
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_go_to_artist)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_person), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        openArtist()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_to_playlist)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = null) },
                onClick = {
                    menuOpen = false
                    addToPlaylist = true
                },
            )
            ShareMenuItem(ShareTarget.Song(entry.songId), onDismiss)
        }
    }
    if (addToPlaylist) AddToPlaylistDialog(songIds = listOf(entry.songId), onDismiss = onDismiss)
}

@Preview(showBackground = true)
@Composable
private fun QueueContentPreview() {
    EarLoftTheme {
        QueueContent(
            summary = "5 songs · 12:34 left",
            state = PlayerState(
                queue = List(5) { QueueEntry(songId = it.toLong(), title = "Song ${it + 1}", artist = "A Band", albumId = null) },
                currentIndex = 1,
            ),
            onBack = {},
            onItemClick = {},
            onRemove = {},
            onMove = { _, _ -> },
            onClear = {},
        )
    }
}
