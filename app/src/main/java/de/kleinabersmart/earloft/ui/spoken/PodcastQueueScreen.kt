package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
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

/** Edit the podcast queue ("Continue listening"): as with music, with drag handle and ✕, plus the search. */
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
                    // The loaded episode stays; if it is the only one, there is nothing to clear.
                    canClear = episodes.valueOrNull.orEmpty().any { it.book.id != currentId },
                    onClear = viewModel::clear,
                )
            },
        )
        PodcastQueueList(onEpisodeClick = onEpisodeClick, modifier = Modifier.navigationBarsPadding(), viewModel = viewModel, selection = selection)
    }
}

/**
 * The queue list with drag handle, ✕ and tap to the episode's page; also in the episode player (sheet or right
 * pane). [transparent]: without its own background for rows that aren't playing (on colored surfaces).
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
 * Queue of episodes or books (both "Continue listening") with drag handle, ✕ and tap. The local copy is
 * reordered immediately while dragging and only saved at the end of the drag via [onReorder] (new order, moved entry).
 * With [selection], a long press shows the episode's menu with "Select" (concept 12.5); during the selection a
 * tap adds or removes the row, drag handle and ✕ are omitted.
 */
@Composable
internal fun SpokenQueueList(
    entries: LoadState<List<BookWithProgress>>,
    currentId: Long?,
    emptyText: String,
    /** Speed if the entry has none of its own (subscription or the area's default). */
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
        // The copy only catches up in the next frame; the list is only empty once the source is empty too.
        is LoadState.Ready -> if (rows.isEmpty() && entries.valueOrNull.isNullOrEmpty()) EmptyHint(emptyText)
    }
    if (rows.isEmpty()) return
    val sameDay = remember(rows) { episodesSharingDay(rows.filter { it.isEpisode }.map { it.book }) }
    FastScrollLazyColumn(state = listState, contentPadding = contentPadding, modifier = modifier) {
        items(rows, key = { it.book.id }) { item ->
            val id = item.book.id
            QueueRow(
                item,
                current = id == currentId,
                withTime = id in sameDay,
                inheritedSpeed = inheritedSpeed(item),
                // While dragged, the row needs a background, otherwise the others show through.
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
                                // Only save if the position changed; otherwise just a tap would fix the row in place.
                                // Without a position change no new state follows; whatever arrived during the drag applies now.
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
 * Search only within the podcast queue: title, summary and podcast name. Structured like the search within
 * a podcast; without drag handle, because a filtered list can't be reordered sensibly.
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
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { viewModel.search(query) }

    val trimmed = query.trim()
    // While typing, the last results stay until the new ones are there; "Nothing found" only for the current text.
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
                // The hits follow the typing; the search key only closes the keyboard, as in the global search.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
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
            else -> {
                val sameDay = remember(hits) { episodesSharingDay(hits.map { it.book }) }
                FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
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
}

/**
 * An episode (date) or a book (author) of the queue with download, title, progress; on the left drag handle (if
 * [dragHandle]) and image. Own row instead of `ListItem`: its paddings for three lines are too generous, here
 * as many entries as possible should fit at a glance. Colors as with `ListItem`.
 */
@Composable
private fun QueueRow(
    item: BookWithProgress,
    current: Boolean,
    withTime: Boolean,
    /** Speed of the subscription or the default, if the episode has none of its own. */
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
            // Long press opens the episode or book menu everywhere (also in the book queue and the queue search), with
            // "Select" only where there is a multi-selection.
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { if (selecting) selection?.toggle(item.book.id) else menu = true },
            )
            .padding(start = 16.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
    ) {
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = selection?.let { { it.toggle(item.book.id) } })
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

/** The small lines above and below the title, slightly tighter than `labelSmall`. */
@Composable
private fun smallLineStyle() = MaterialTheme.typography.labelSmall.copy(lineHeight = 14.sp)

/** First line: date of the episode, followed by the download state as in the lists (downloaded: check mark, otherwise nothing). */
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

/** First line for books: author, followed by the download state as for episodes. */
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

/** Last line: played time, small bar, remaining time as in the player (at the set speed). Nothing without a duration. */
@Composable
private fun EpisodeProgress(item: BookWithProgress, inheritedSpeed: Float) {
    val remainingMs = item.remainingPlayMs(inheritedSpeed) ?: return
    val durationMs = item.progress?.durationMs?.takeIf { it > 0 } ?: item.book.durationMs
    val positionMs = item.progress?.positionMs?.coerceIn(0, durationMs) ?: 0
    val style = smallLineStyle()
    // Equal-width times so the bars are equally long below each other.
    val measurer = rememberTextMeasurer()
    val timeWidth = with(LocalDensity.current) { measurer.measure("-0:00:00", style).size.width.toDp() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatClock(positionMs), style = style, maxLines = 1, modifier = Modifier.widthIn(min = timeWidth))
        LinearProgressIndicator(
            progress = { positionMs.toFloat() / durationMs },
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            // The default track has the color of the current episode's row.
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
