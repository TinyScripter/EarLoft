package de.kleinabersmart.tinyamp.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import de.kleinabersmart.tinyamp.ui.components.reorderActions
import de.kleinabersmart.tinyamp.ui.valueOrNull
import de.kleinabersmart.tinyamp.ui.library.BackButton
import java.text.DateFormat
import java.util.Date
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

/** Die Warteschlange der Podcasts („Weiterhören“) bearbeiten: wie bei der Musik mit Ziehgriff und ✕, dazu die Suche. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastQueueScreen(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.player_queue)) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                IconButton(onClick = onSearch) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_queue_search))
                }
            },
        )
        PodcastQueueList(onEpisodeClick = onEpisodeClick, modifier = Modifier.navigationBarsPadding())
    }
}

/**
 * Die Liste der Warteschlange mit Ziehgriff, ✕ und Tipp zur Seite der Folge; auch im Folgen-Player (Sheet bzw. rechter
 * Bereich). [transparent]: ohne eigenen Hintergrund für Zeilen, die nicht laufen (auf farbigen Flächen).
 */
@Composable
fun PodcastQueueList(
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    transparent: Boolean = false,
    viewModel: PodcastQueueViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Die lokale Kopie wird beim Ziehen sofort umsortiert und erst am Ende des Ziehens gespeichert.
    val rows = remember { mutableStateListOf<BookWithProgress>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
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
            QueueRow(
                item,
                current = id == currentId,
                // Gezogen braucht die Zeile einen Hintergrund, sonst scheinen die anderen durch.
                transparent = transparent && !isDragged,
                onClick = { onEpisodeClick(id) },
                onRemove = { viewModel.remove(id) },
                dragHandle = {
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
                },
                modifier = Modifier
                    .zIndex(if (isDragged) 1f else 0f)
                    .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                    .reorderActions(rows.indexOf(item), rows.size) { from, to ->
                        rows.add(to, rows.removeAt(from))
                        viewModel.reorder(rows.map { it.book.id })
                    },
            )
        }
    }
}

/**
 * Suche nur in der Warteschlange der Podcasts: Überschrift, Inhaltsangabe und Name des Podcasts. Aufbau wie die Suche in
 * einem Podcast; ohne Ziehgriff, denn eine gefilterte Liste lässt sich nicht sinnvoll umsortieren.
 */
@Composable
fun PodcastQueueSearchScreen(
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastQueueViewModel = hiltViewModel(),
) {
    val result by viewModel.searchHits.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { viewModel.search(query) }

    val trimmed = query.trim()
    // Beim Tippen bleiben die letzten Treffer stehen, bis die neuen da sind; „Nichts gefunden“ erst für den aktuellen Text.
    val hits = result?.hits.orEmpty()
    val settled = result?.query == trimmed

    Column(modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        Text(
            stringResource(R.string.podcast_queue_search),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 68.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
        )
        when {
            trimmed.isEmpty() -> Unit
            hits.isEmpty() -> if (settled) EmptyHint(stringResource(R.string.search_no_results, trimmed))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                items(hits, key = { it.book.id }) { item ->
                    QueueRow(
                        item,
                        current = item.book.id == currentId,
                        transparent = false,
                        onClick = { onEpisodeClick(item.book.id) },
                        onRemove = { viewModel.remove(item.book.id) },
                    )
                }
            }
        }
    }
}

/**
 * Eine Folge der Warteschlange: Datum und Download, Titel, Fortschritt; links Ziehgriff (falls [dragHandle]) und Bild.
 * Eigene Zeile statt `ListItem`: Dessen Abstände für drei Zeilen sind zu großzügig, hier sollen möglichst viele Folgen
 * auf einen Blick passen. Farben wie beim `ListItem`.
 */
@Composable
private fun QueueRow(
    item: BookWithProgress,
    current: Boolean,
    transparent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val background = when {
        current -> colors.secondaryContainer
        transparent -> Color.Transparent
        else -> colors.surface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(background)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        dragHandle?.invoke()
        BookArt(item.book.id, Modifier.padding(start = 4.dp).size(48.dp), MaterialTheme.shapes.small)
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 4.dp)) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { EpisodeFacts(item) }
            Text(
                item.book.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { EpisodeProgress(item) }
        }
        IconButton(onClick = onRemove) {
            Icon(
                painterResource(DesignR.drawable.ic_close),
                contentDescription = stringResource(R.string.action_remove_from_queue),
            )
        }
    }
}

/** Die kleinen Zeilen über und unter dem Titel, etwas enger als `labelSmall`. */
@Composable
private fun smallLineStyle() = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)

/** Erste Zeile: Datum der Folge, dahinter der Download-Stand wie in den Listen (geladen: Haken, sonst nichts). */
@Composable
private fun EpisodeFacts(item: BookWithProgress) {
    val book = item.book
    val mark = LocalBookDownloads.current[book.id]?.state?.offlineMark
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (book.addedAt > 0) {
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(book.addedAt)), style = smallLineStyle(), maxLines = 1)
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
    val style = smallLineStyle()
    // Gleich breite Zeiten, damit die Balken untereinander gleich lang sind.
    val measurer = rememberTextMeasurer()
    val timeWidth = with(LocalDensity.current) { measurer.measure("-0:00:00", style).size.width.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
