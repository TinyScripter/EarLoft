package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.model.BookWithProgress
import de.kleinabersmart.kleinaberlaut.ui.LoadState
import de.kleinabersmart.kleinaberlaut.ui.components.EmptyHint
import de.kleinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import de.kleinabersmart.kleinaberlaut.ui.components.LoadingIndicator
import de.kleinabersmart.kleinaberlaut.ui.valueOrNull
import de.kleinabersmart.kleinaberlaut.ui.library.BackButton
import de.kleinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Die Warteschlange der Podcasts („Weiterhören“) bearbeiten: wie bei der Musik mit Ziehgriff und ✕. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastQueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.player_queue)) },
            navigationIcon = { BackButton(onBack) },
        )
        PodcastQueueList(modifier = Modifier.navigationBarsPadding())
    }
}

/**
 * Die Liste der Warteschlange mit Ziehgriff, ✕ und Tipp zum Abspielen; auch im Folgen-Player (Sheet bzw. rechter Bereich).
 * [transparent]: ohne eigenen Hintergrund für Zeilen, die nicht laufen (auf farbigen Flächen); [onPlayed] nach dem Tipp
 * auf eine Folge (das Sheet schließt sich dann).
 */
@Composable
fun PodcastQueueList(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    transparent: Boolean = false,
    onPlayed: () -> Unit = {},
    viewModel: PodcastQueueViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Die lokale Kopie wird beim Ziehen sofort umsortiert und erst am Ende des Ziehens gespeichert.
    val rows = remember { mutableStateListOf<BookWithProgress>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(episodes) {
        val loaded = episodes.valueOrNull
        if (draggedId != null || loaded == null) return@LaunchedEffect
        rows.clear()
        rows.addAll(loaded)
    }

    when (episodes) {
        LoadState.Loading -> LoadingIndicator()
        LoadState.Missing, LoadState.Failed -> LoadFailedHint()
        // Die Kopie zieht erst im nächsten Frame nach; leer ist die Liste erst, wenn auch die Quelle leer ist.
        is LoadState.Ready -> if (rows.isEmpty() && episodes.valueOrNull.isNullOrEmpty()) {
            EmptyHint(stringResource(R.string.podcast_queue_empty))
        }
    }
    if (rows.isEmpty()) return
    LazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier) {
        items(rows, key = { it.book.id }) { item ->
            val id = item.book.id
            val isDragged = id == draggedId
            ListItem(
                headlineContent = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(item.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painterResource(DesignR.drawable.ic_drag_handle),
                            contentDescription = stringResource(R.string.action_reorder_queue_item),
                            modifier = Modifier
                                .size(40.dp)
                                .padding(8.dp)
                                .pointerInput(id) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedId = id
                                            dragOffset = 0f
                                        },
                                        onDragEnd = {
                                            draggedId = null
                                            dragOffset = 0f
                                            viewModel.reorder(rows.map { it.book.id })
                                        },
                                        onDragCancel = {
                                            draggedId = null
                                            dragOffset = 0f
                                            viewModel.reorder(rows.map { it.book.id })
                                        },
                                    ) { change, drag ->
                                        change.consume()
                                        dragOffset += drag.y
                                        val from = rows.indexOfFirst { it.book.id == id }
                                        val height = listState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == id }?.size?.toFloat() ?: return@detectDragGestures
                                        val to = when {
                                            dragOffset > height / 2 -> from + 1
                                            dragOffset < -height / 2 -> from - 1
                                            else -> from
                                        }
                                        if (to != from && to in rows.indices) {
                                            rows.add(to, rows.removeAt(from))
                                            dragOffset -= (to - from) * height
                                        }
                                    }
                                },
                        )
                        BookArt(id, Modifier.padding(start = 4.dp).size(48.dp), MaterialTheme.shapes.small)
                    }
                },
                trailingContent = {
                    IconButton(onClick = { viewModel.remove(id) }) {
                        Icon(
                            painterResource(DesignR.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_remove_from_queue),
                        )
                    }
                },
                colors = when {
                    id == currentId -> ListItemDefaults.colors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    // Gezogen braucht die Zeile einen Hintergrund, sonst scheinen die anderen durch.
                    transparent && !isDragged -> ListItemDefaults.colors(containerColor = Color.Transparent)
                    else -> ListItemDefaults.colors()
                },
                modifier = Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .clickable {
                        viewModel.play(id)
                        onPlayed()
                    },
            )
        }
    }
}
