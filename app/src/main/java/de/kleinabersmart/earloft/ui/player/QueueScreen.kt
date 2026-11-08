package de.kleinabersmart.earloft.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
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
import de.kleinabersmart.earloft.ui.components.reorderActions
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QueueContent(
        state = state,
        onBack = onBack,
        onItemClick = viewModel::skipToQueueItem,
        onRemove = viewModel::removeQueueItem,
        onMove = viewModel::moveQueueItem,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueContent(
    state: PlayerState,
    onBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    onMove: (from: Int, to: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.player_queue)) },
            navigationIcon = { BackButton(onBack) },
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

/** Die Warteschlange mit Ziehgriffen; auch rechts neben der Wiedergabe auf breiten Fenstern. */
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

        // Einträge können doppelt vorkommen, daher bekommt jede Zeile eine eigene Kennung als Schlüssel.
        // Die lokale Kopie wird beim Ziehen sofort umsortiert; der Player zieht danach nach.
        var nextRowId by remember { mutableIntStateOf(0) }
        val rows = remember { mutableStateListOf<QueueRow>() }
        var currentIndex by remember { mutableIntStateOf(state.currentIndex) }
        var draggedId by remember { mutableStateOf<Int?>(null) }
        var dragOffset by remember { mutableFloatStateOf(0f) }
        LaunchedEffect(state.queue, state.currentIndex) {
            if (draggedId != null) return@LaunchedEffect
            rows.clear()
            state.queue.forEach { rows.add(QueueRow(nextRowId++, it)) }
            currentIndex = state.currentIndex
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
                val isDragged = row.id == draggedId
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
                                    .pointerInput(row.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedId = row.id
                                                dragOffset = 0f
                                            },
                                            onDragEnd = { draggedId = null; dragOffset = 0f },
                                            onDragCancel = { draggedId = null; dragOffset = 0f },
                                        ) { change, drag ->
                                            change.consume()
                                            dragOffset += drag.y
                                            val from = rows.indexOfFirst { it.id == row.id }
                                            val height = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == row.id }?.size?.toFloat() ?: return@detectDragGestures
                                            val to = when {
                                                dragOffset > height / 2 -> from + 1
                                                dragOffset < -height / 2 -> from - 1
                                                else -> from
                                            }
                                            if (to != from && to in rows.indices) {
                                                moveRow(from, to)
                                                dragOffset -= (to - from) * height
                                            }
                                        }
                                    },
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
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .clickable { onItemClick(rows.indexOf(row)) }
                        .reorderActions(index, rows.size, moveRow),
                )
            }
        }
    }
}

private data class QueueRow(val id: Int, val entry: QueueEntry)

@Preview(showBackground = true)
@Composable
private fun QueueContentPreview() {
    EarLoftTheme {
        QueueContent(
            state = PlayerState(
                queue = List(5) { QueueEntry(songId = it.toLong(), title = "Lied ${it + 1}", artist = "Eine Band", albumId = null) },
                currentIndex = 1,
            ),
            onBack = {},
            onItemClick = {},
            onRemove = {},
            onMove = { _, _ -> },
        )
    }
}
