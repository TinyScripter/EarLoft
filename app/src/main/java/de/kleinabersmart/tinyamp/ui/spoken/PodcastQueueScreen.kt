package de.kleinabersmart.tinyamp.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.model.BookWithProgress
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.components.EmptyHint
import de.kleinabersmart.tinyamp.ui.components.LoadFailedHint
import de.kleinabersmart.tinyamp.ui.components.LoadingIndicator
import de.kleinabersmart.tinyamp.ui.components.MarkGap
import de.kleinabersmart.tinyamp.ui.components.OfflineMarkIcon
import de.kleinabersmart.tinyamp.ui.valueOrNull
import de.kleinabersmart.tinyamp.ui.library.BackButton
import java.text.DateFormat
import java.util.Date
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

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
                overlineContent = { EpisodeFacts(item) },
                headlineContent = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    Column {
                        Text(item.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        EpisodeProgress(item)
                    }
                },
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

/** Erste Zeile: Datum der Folge, dahinter der Download-Stand wie in den Listen (geladen: Haken, sonst nichts). */
@Composable
private fun EpisodeFacts(item: BookWithProgress) {
    val book = item.book
    val mark = LocalBookDownloads.current[book.id]?.state?.offlineMark
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (book.addedAt > 0) {
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.addedAt)), maxLines = 1)
        }
        OfflineMarkIcon(mark, Modifier.padding(start = MarkGap).size(14.dp))
    }
}

/** Letzte Zeile: gehörte Zeit, kleiner Balken, Restzeit wie im Player (beim eingestellten Tempo). Ohne Dauer nichts. */
@Composable
private fun EpisodeProgress(item: BookWithProgress) {
    val progress = item.progress
    val durationMs = progress?.durationMs?.takeIf { it > 0 } ?: item.book.durationMs
    if (durationMs <= 0) return
    val positionMs = progress?.positionMs?.coerceIn(0, durationMs) ?: 0
    val speed = progress?.speed?.takeIf { it > 0f } ?: 1f
    val remainingMs = ((durationMs - positionMs) / speed).toLong()
    val style = MaterialTheme.typography.labelSmall
    // Gleich breite Zeiten, damit die Balken untereinander gleich lang sind.
    val measurer = rememberTextMeasurer()
    val timeWidth = with(LocalDensity.current) { measurer.measure("-0:00:00", style).size.width.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text(formatClock(positionMs), style = style, maxLines = 1, modifier = Modifier.widthIn(min = timeWidth))
        LinearProgressIndicator(
            progress = { positionMs.toFloat() / durationMs },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            // Die Standardspur hat die Farbe der Zeile der laufenden Folge.
            trackColor = LocalContentColor.current.copy(alpha = 0.2f),
            drawStopIndicator = {},
        )
        Text(
            "-" + formatClock(remainingMs),
            style = style,
            maxLines = 1,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = timeWidth),
        )
    }
}
