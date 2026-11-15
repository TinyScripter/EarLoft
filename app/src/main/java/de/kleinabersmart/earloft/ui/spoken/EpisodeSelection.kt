package de.kleinabersmart.earloft.ui.spoken

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Ausgewählte Folgen einer Liste (Mehrfachauswahl, Konzept 12.5); leer = keine Auswahl. Sie beginnt über „Auswählen“ im
 * Langklick-Menü; danach schaltet ein Tipp (auch ein Langklick) eine Folge dazu oder weg.
 */
@Stable
class EpisodeSelection internal constructor(initial: Collection<Long>) {
    var ids: Set<Long> by mutableStateOf(initial.toSet())
        private set

    val active: Boolean get() = ids.isNotEmpty()

    operator fun contains(bookId: Long): Boolean = bookId in ids

    fun toggle(bookId: Long) {
        ids = if (bookId in ids) ids - bookId else ids + bookId
    }

    /** Alle [shown] auswählen; sind schon alle ausgewählt, keine mehr (dann endet die Auswahl). */
    fun toggleAll(shown: Collection<Long>) {
        ids = if (ids.containsAll(shown)) emptySet() else shown.toSet()
    }

    fun clear() {
        ids = emptySet()
    }

    /** Was nicht mehr in der Liste steht (anderer Filter, aus der Warteschlange gefallen), ist nicht mehr ausgewählt. */
    internal fun retain(shown: Collection<Long>) {
        if (!active) return
        val kept = ids.intersect(shown.toSet())
        if (kept.size != ids.size) ids = kept
    }
}

@Composable
fun rememberEpisodeSelection(): EpisodeSelection =
    rememberSaveable(saver = listSaver({ it.ids.toList() }, { EpisodeSelection(it) })) { EpisodeSelection(emptyList()) }

/**
 * Leiste statt der Kopfzeile, solange ausgewählt ist: ✕ beendet die Auswahl (wie „Zurück“), daneben die Anzahl; rechts
 * „Alle auswählen“, „Gehört“ und „Laden“, im ⋮ „Download löschen“, Warteschlange, „Als Nächstes“ und „Merken“. [shown] sind die Folgen
 * der Liste in ihrer Reihenfolge; die Aktionen gelten für die ausgewählten darunter, in dieser Reihenfolge. [inQueue]: Die
 * Liste ist die Warteschlange (dort „Entfernen“ statt „Zur Warteschlange“). Nach einer Aktion endet die Auswahl.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpisodeSelectionBar(
    selection: EpisodeSelection,
    shown: List<BookWithProgress>,
    modifier: Modifier = Modifier,
    inQueue: Boolean = false,
    /** Unter dem Kopf der App (Reiter „Folgen“) ohne Abstand für die Statusleiste. */
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    viewModel: EpisodeMenuViewModel = hiltViewModel(),
) {
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val trialFeeds by viewModel.trialFeeds.collectAsStateWithLifecycle()
    // Abonniert, damit „Als Nächstes“ die vorderste Folge kennt.
    viewModel.queueHead.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val selected = shown.filter { it.book.id in selection }
    val count = selected.size
    val allPlayed = selected.isNotEmpty() && selected.all { it.progress?.finished == true }
    var menu by remember { mutableStateOf(false) }

    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(shown) { selection.retain(shown.map { it.book.id }) }

    fun done(text: String) {
        selection.clear()
        snackbars.show(text)
    }

    TopAppBar(
        title = { Text(pluralStringResource(R.plurals.episodes_selected, count, count)) },
        navigationIcon = {
            IconButton(onClick = selection::clear) {
                Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.episode_selection_end))
            }
        },
        actions = {
            IconButton(onClick = { selection.toggleAll(shown.map { it.book.id }) }) {
                Icon(painterResource(DesignR.drawable.ic_done_all), contentDescription = stringResource(R.string.episode_select_all))
            }
            IconButton(onClick = {
                val played = !allPlayed
                val text = quantity(resources, if (played) R.plurals.episodes_marked_played else R.plurals.episodes_marked_unplayed, count)
                viewModel.setPlayed(selected, played) { restore ->
                    snackbars.show(text, SnackbarDuration.Long, resources.getString(R.string.spoken_bookmark_undo)) { viewModel.undo(restore) }
                }
                selection.clear()
            }) {
                Icon(
                    painterResource(if (allPlayed) DesignR.drawable.ic_replay else DesignR.drawable.ic_check),
                    contentDescription = stringResource(if (allPlayed) R.string.episode_mark_unplayed else R.string.episode_mark_played),
                )
            }
            // Folgen vom Gerät sind schon da.
            if (selected.any { !it.book.isLocal && it.book.id !in downloaded }) {
                IconButton(onClick = {
                    viewModel.download(selected) { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
                    selection.clear()
                }) {
                    Icon(painterResource(DesignR.drawable.ic_download), contentDescription = stringResource(R.string.action_download))
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (selected.any { it.book.id in downloaded }) DropdownMenuItem(
                        text = { Text(stringResource(R.string.episode_delete_download)) },
                        onClick = {
                            menu = false
                            viewModel.removeDownloads(selected)
                            done(quantity(resources, R.plurals.episodes_downloads_removed, selected.count { it.book.id in downloaded }))
                        },
                    )
                    if (inQueue || selected.all { it.book.id in queued }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.episode_queue_remove)) },
                            onClick = {
                                menu = false
                                viewModel.removeFromQueue(selected)
                                done(quantity(resources, R.plurals.episodes_removed_from_queue, count))
                            },
                        )
                    } else {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.episode_queue_add)) },
                            onClick = {
                                menu = false
                                viewModel.append(selected)
                                done(quantity(resources, R.plurals.episodes_queued, count))
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.episode_play_next)) },
                        onClick = {
                            menu = false
                            viewModel.playNext(selected)
                            done(quantity(resources, R.plurals.episodes_play_next, count))
                        },
                    )
                    // Sind alle gemerkt: nicht mehr merken; Folgen von Probeabos lassen sich nicht merken.
                    val starrable = selected.filter { it.book.serverId !in trialFeeds }
                    if (starrable.isNotEmpty()) {
                        val allStarred = starrable.all { it.book.starred }
                        DropdownMenuItem(
                            text = { Text(stringResource(if (allStarred) R.string.episode_unstar else R.string.episode_star)) },
                            onClick = {
                                menu = false
                                viewModel.setStarred(starrable, !allStarred)
                                done(quantity(resources, if (allStarred) R.plurals.episodes_unstarred else R.plurals.episodes_starred, starrable.size))
                            },
                        )
                    }
                }
            }
        },
        windowInsets = windowInsets,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = modifier,
    )
}

private fun quantity(resources: android.content.res.Resources, id: Int, count: Int): String =
    resources.getQuantityString(id, count, count)

/** [BookArt] mit Haken darüber, wenn die Folge ausgewählt ist. */
@Composable
fun SelectableArt(bookId: Long, selected: Boolean, modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.small) {
    Box(modifier) {
        BookArt(bookId, Modifier.fillMaxSize(), shape)
        SelectedMark(selected, shape, Modifier.matchParentSize())
    }
}

/** Abgedunkelte Fläche mit Haken über einem Bild, solange [selected]. */
@Composable
fun SelectedMark(selected: Boolean, shape: Shape, modifier: Modifier = Modifier) {
    if (!selected) return
    Box(
        modifier.clip(shape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
    }
}

/**
 * Wischen auf einer Folgenzeile (Konzept 12.5, abschaltbar unter Einstellungen → Podcasts): nach rechts in die
 * Warteschlange (steht sie schon darin: heraus), nach links „gehört“ (ist sie es schon: „ungehört“). Die Zeile gleitet
 * danach zurück; „Gehört“ lässt sich in der Snackbar rückgängig machen. Die im Player geladene Folge hielte dabei an, ohne
 * dass sich das zurücknehmen ließe; für sie fragt die Snackbar erst nach.
 */
@Composable
fun EpisodeSwipeBox(
    item: BookWithProgress,
    enabled: Boolean,
    content: @Composable () -> Unit,
) {
    if (!enabled) {
        content()
        return
    }
    val viewModel: EpisodeMenuViewModel = hiltViewModel()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val loadedBookId by viewModel.loadedBookId.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val state = rememberSwipeToDismissBoxState()
    val inQueue = item.book.id in queued
    val played = item.progress?.finished == true
    val loaded = item.book.id == loadedBookId
    val handle by rememberUpdatedState { direction: SwipeToDismissBoxValue ->
        when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> if (inQueue) {
                viewModel.removeFromQueue(listOf(item))
                snackbars.show(resources.getString(R.string.queue_removed, item.book.title))
            } else {
                viewModel.append(listOf(item))
                snackbars.show(resources.getString(R.string.queue_added_at_end, item.book.title))
            }
            SwipeToDismissBoxValue.EndToStart -> if (loaded && !played) {
                snackbars.show(
                    resources.getString(R.string.episode_swipe_loaded, item.book.title),
                    SnackbarDuration.Long,
                    resources.getString(R.string.episode_mark_played),
                ) { viewModel.setPlayed(item, true) }
            } else {
                val text = resources.getString(if (played) R.string.episode_marked_unplayed else R.string.episode_marked_played, item.book.title)
                viewModel.setPlayed(listOf(item), !played) { restore ->
                    snackbars.show(text, SnackbarDuration.Long, resources.getString(R.string.spoken_bookmark_undo)) { viewModel.undo(restore) }
                }
            }
            SwipeToDismissBoxValue.Settled -> Unit
        }
        scope.launch { state.reset() }
        Unit
    }
    // Gleichbleibend: Die Box ruft ein neues Lambda erneut auf, solange sie nicht zurückgeglitten ist – und die Aktion
    // selbst ändert die Zeile (Warteschlange, gehört).
    val onDismiss: (SwipeToDismissBoxValue) -> Unit = remember { { direction -> handle(direction) } }
    SwipeToDismissBox(
        state = state,
        onDismiss = onDismiss,
        backgroundContent = {
            val direction = state.dismissDirection
            if (direction == SwipeToDismissBoxValue.Settled) return@SwipeToDismissBox
            val start = direction == SwipeToDismissBoxValue.StartToEnd
            Box(
                Modifier
                    .fillMaxSize()
                    .background(if (start) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 24.dp),
                contentAlignment = if (start) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                val (icon, label) = when {
                    start && inQueue -> DesignR.drawable.ic_close to R.string.episode_queue_remove
                    start -> DesignR.drawable.ic_playlist_add to R.string.episode_queue_add
                    played -> DesignR.drawable.ic_replay to R.string.episode_mark_unplayed
                    else -> DesignR.drawable.ic_check to R.string.episode_mark_played
                }
                Icon(
                    painterResource(icon),
                    contentDescription = stringResource(label),
                    tint = if (start) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        },
    ) {
        content()
    }
}
