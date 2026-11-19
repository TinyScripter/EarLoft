package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.MarkGap
import de.kleinabersmart.earloft.ui.components.OfflineMarkIcon
import de.kleinabersmart.earloft.ui.components.QueueMoreMenu
import de.kleinabersmart.earloft.ui.components.QueueTitle
import de.kleinabersmart.earloft.ui.components.rememberReorderState
import de.kleinabersmart.earloft.ui.components.reorderActions
import de.kleinabersmart.earloft.ui.components.reorderHandle
import de.kleinabersmart.earloft.ui.components.reorderableItem
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Die Warteschlange der Podcasts („Weiterhören“) bearbeiten: wie bei der Musik mit Ziehgriff und ✕, dazu die Suche. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastQueueScreen(
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastQueueViewModel = hiltViewModel(),
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val selection = rememberEpisodeSelection()
    Column(modifier.fillMaxSize()) {
        if (selection.active) {
            EpisodeSelectionBar(selection, episodes.valueOrNull.orEmpty(), loaded = episodes is LoadState.Ready, inQueue = true)
        } else TopAppBar(
            title = {
                QueueTitle(
                    summary?.let { pluralStringResource(R.plurals.podcast_queue_summary, it.count, it.count, formatDuration(it.remainingMs)) },
                )
            },
            navigationIcon = { BackButton(onBack) },
            actions = {
                IconButton(onClick = onSearch) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_queue_search))
                }
                QueueMoreMenu(
                    clearMessage = stringResource(R.string.podcast_queue_clear_message),
                    // Die geladene Folge bleibt; steht nur sie da, gibt es nichts zu leeren.
                    canClear = episodes.valueOrNull.orEmpty().any { it.book.id != currentId },
                    onClear = viewModel::clear,
                )
            },
        )
        PodcastQueueList(onEpisodeClick = onEpisodeClick, modifier = Modifier.navigationBarsPadding(), viewModel = viewModel, selection = selection)
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
    selection: EpisodeSelection? = null,
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val feedSpeeds by viewModel.feedSpeeds.collectAsStateWithLifecycle()
    SpokenQueueList(
        entries = episodes,
        currentId = currentId,
        emptyText = stringResource(R.string.podcast_queue_empty),
        inheritedSpeed = { feedSpeeds[it.book.serverId] ?: 1f },
        onClick = onEpisodeClick,
        onRemove = viewModel::remove,
        onReorder = viewModel::reorder,
        modifier = modifier,
        contentPadding = contentPadding,
        transparent = transparent,
        selection = selection,
    )
}

/**
 * Warteschlange von Folgen oder Büchern (beide „Weiterhören“) mit Ziehgriff, ✕ und Tipp. Die lokale Kopie wird beim
 * Ziehen sofort umsortiert und erst am Ende des Ziehens über [onReorder] gespeichert (neue Reihenfolge, bewegter Eintrag).
 * Mit [selection] zeigt ein Langklick das Menü der Folge mit „Auswählen“ (Konzept 12.5); während der Auswahl schaltet ein
 * Tipp die Zeile dazu oder weg, Ziehgriff und ✕ entfallen.
 */
@Composable
internal fun SpokenQueueList(
    entries: LoadState<List<BookWithProgress>>,
    currentId: Long?,
    emptyText: String,
    /** Tempo, wenn der Eintrag kein eigenes hat (Abo bzw. Vorgabe des Bereichs). */
    inheritedSpeed: (BookWithProgress) -> Float,
    onClick: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    onReorder: (bookIds: List<Long>, movedId: Long) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    transparent: Boolean = false,
    selection: EpisodeSelection? = null,
) {
    val listState = rememberLazyListState()
    val selecting = selection?.active == true
    val rows = remember { mutableStateListOf<BookWithProgress>() }
    val reorder = rememberReorderState(listState)
    val latest by rememberUpdatedState(entries)
    fun syncRows() {
        val loaded = latest.valueOrNull ?: return
        rows.clear()
        rows.addAll(loaded)
    }
    LaunchedEffect(entries) {
        if (reorder.draggedKey == null) syncRows()
    }

    when (entries) {
        LoadState.Loading -> LoadingIndicator()
        LoadState.Missing, LoadState.Failed -> LoadFailedHint()
        // Die Kopie zieht erst im nächsten Frame nach; leer ist die Liste erst, wenn auch die Quelle leer ist.
        is LoadState.Ready -> if (rows.isEmpty() && entries.valueOrNull.isNullOrEmpty()) EmptyHint(emptyText)
    }
    if (rows.isEmpty()) return
    val sameDay = episodesSharingDay(rows.filter { it.isEpisode }.map { it.book })
    FastScrollLazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier) {
        items(rows, key = { it.book.id }) { item ->
            val id = item.book.id
            QueueRow(
                item,
                current = id == currentId,
                withTime = id in sameDay,
                inheritedSpeed = inheritedSpeed(item),
                // Gezogen braucht die Zeile einen Hintergrund, sonst scheinen die anderen durch.
                transparent = transparent && !reorder.isLifted(id),
                onClick = { if (selecting) selection.toggle(id) else onClick(id) },
                onRemove = { onRemove(id) },
                selection = selection,
                dragHandle = if (selecting) null else { {
                    Icon(
                        painterResource(DesignR.drawable.ic_drag_handle),
                        contentDescription = stringResource(R.string.action_reorder_queue_item),
                        modifier = Modifier
                            .size(40.dp)
                            .padding(8.dp)
                            .reorderHandle(
                                reorder,
                                id,
                                index = { rows.indexOfFirst { it.book.id == id } },
                                onMove = { from, to -> rows.add(to, rows.removeAt(from)) },
                                // Nur speichern, wenn sich der Platz geändert hat; sonst würde schon ein Antippen die Zeile festlegen.
                                // Ohne Platzwechsel kommt kein neuer Stand nach; was beim Ziehen eintraf, gilt jetzt.
                                onDragEnd = { moved -> if (moved) onReorder(rows.map { it.book.id }, id) else syncRows() },
                            ),
                    )
                } },
                modifier = Modifier
                    .reorderableItem(this, reorder, id)
                    .reorderActions(rows.indexOf(item), rows.size) { from, to ->
                        rows.add(to, rows.removeAt(from))
                        onReorder(rows.map { it.book.id }, id)
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
    val feedSpeeds by viewModel.feedSpeeds.collectAsStateWithLifecycle()
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
            else -> FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                val sameDay = episodesSharingDay(hits.map { it.book })
                items(hits, key = { it.book.id }) { item ->
                    QueueRow(
                        item,
                        current = item.book.id == currentId,
                        withTime = item.book.id in sameDay,
                        inheritedSpeed = feedSpeeds[item.book.serverId] ?: 1f,
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
 * Eine Folge (Datum) oder ein Buch (Autor) der Warteschlange mit Download, Titel, Fortschritt; links Ziehgriff (falls
 * [dragHandle]) und Bild. Eigene Zeile statt `ListItem`: Dessen Abstände für drei Zeilen sind zu großzügig, hier sollen
 * möglichst viele Einträge auf einen Blick passen. Farben wie beim `ListItem`.
 */
@Composable
private fun QueueRow(
    item: BookWithProgress,
    current: Boolean,
    withTime: Boolean,
    /** Tempo des Abos bzw. der Vorgabe, wenn die Folge kein eigenes hat. */
    inheritedSpeed: Float,
    transparent: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    selection: EpisodeSelection? = null,
    dragHandle: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val selecting = selection?.active == true
    val selected = selection != null && item.book.id in selection
    var menu by remember { mutableStateOf(false) }
    val background = when {
        selected -> colors.tertiaryContainer
        current -> colors.secondaryContainer
        transparent -> Color.Transparent
        else -> colors.surface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .background(background)
            .then(
                if (selection == null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        onClick = onClick,
                        onLongClick = { if (selecting) selection.toggle(item.book.id) else menu = true },
                    )
                },
            )
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (selection != null) {
            EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = { selection.toggle(item.book.id) })
        }
        dragHandle?.invoke()
        SelectableArt(item.book.id, selected, Modifier.padding(start = 4.dp).size(48.dp))
        Column(Modifier.weight(1f).padding(start = 16.dp, end = 4.dp)) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) {
                if (item.isEpisode) EpisodeFacts(item, withTime) else BookFacts(item)
            }
            Text(
                item.book.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (current) colors.onSecondaryContainer else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { EpisodeProgress(item, inheritedSpeed) }
        }
        if (!selecting) {
            IconButton(onClick = onRemove) {
                Icon(
                    painterResource(DesignR.drawable.ic_close),
                    contentDescription = stringResource(R.string.action_remove_from_queue),
                )
            }
        }
    }
}

/** Die kleinen Zeilen über und unter dem Titel, etwas enger als `labelSmall`. */
@Composable
private fun smallLineStyle() = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)

/** Erste Zeile: Datum der Folge, dahinter der Download-Stand wie in den Listen (geladen: Haken, sonst nichts). */
@Composable
private fun EpisodeFacts(item: BookWithProgress, withTime: Boolean) {
    val book = item.book
    val mark = LocalBookDownloads.current[book.id]?.offlineMark
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (book.addedAt > 0) {
            Text(formatEpisodeDay(book.addedAt, withTime), style = smallLineStyle(), maxLines = 1)
        }
        OfflineMarkIcon(mark, Modifier.padding(start = MarkGap).size(14.dp))
    }
}

/** Erste Zeile bei Büchern: Autor, dahinter der Download-Stand wie bei den Folgen. */
@Composable
private fun BookFacts(item: BookWithProgress) {
    val mark = LocalBookDownloads.current[item.book.id]?.offlineMark
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (item.book.author.isNotEmpty()) {
            Text(item.book.author, style = smallLineStyle(), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        OfflineMarkIcon(mark, Modifier.padding(start = MarkGap).size(14.dp))
    }
}

/** Letzte Zeile: gehörte Zeit, kleiner Balken, Restzeit wie im Player (beim eingestellten Tempo). Ohne Dauer nichts. */
@Composable
private fun EpisodeProgress(item: BookWithProgress, inheritedSpeed: Float) {
    val remainingMs = item.remainingPlayMs(inheritedSpeed) ?: return
    val durationMs = item.progress?.durationMs?.takeIf { it > 0 } ?: item.book.durationMs
    val positionMs = item.progress?.positionMs?.coerceIn(0, durationMs) ?: 0
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
