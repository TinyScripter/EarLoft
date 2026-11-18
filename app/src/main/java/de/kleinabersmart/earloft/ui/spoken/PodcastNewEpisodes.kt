package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import androidx.compose.ui.semantics.selected as selectedSemantics
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Reiter „Neu“ der Podcast-Bibliothek: die neuesten Folgen aller Abos untereinander, neueste oben, auch gehörte
 * ([de.kleinabersmart.earloft.core.data.podcast.PodcastRepository.latestEpisodes]), als [NewEpisodeRow]. Mehrfachauswahl über
 * [selection], ohne Wischen (der Reiter sitzt im Pager, wie „Gemerkt“).
 */
@Composable
fun NewEpisodes(
    onEpisodeClick: (Long) -> Unit,
    selection: EpisodeSelection,
    viewModel: PodcastNewViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val speeds by viewModel.feedSpeeds.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }
    LoadableList(episodes, stringResource(R.string.podcast_new_empty)) { list ->
        val listState = rememberLazyListState()
        // Kommt eine neue Folge dazu, steht sie oben im Blick statt über dem Rand.
        ShowStartOnNewFirst(list.firstOrNull()?.book?.id?.toString()) { listState.scrollToItem(0) }
        val sameDay = episodesSharingDay(list.map { it.book })
        LazyColumn(
            Modifier.fillMaxSize().readableWidth(TwoColumnMinWidth),
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        ) {
            groupedItems(list, key = { it.book.id }) { item ->
                val id = item.book.id
                NewEpisodeRow(
                    item,
                    withTime = id in sameDay,
                    remainingMs = item.remainingPlayMs(speeds[item.book.serverId] ?: 1f),
                    download = downloadStates[id] ?: TrackDownload.NONE,
                    downloadFraction = downloadProgress[id],
                    queued = id in queued,
                    playing = id == playingId,
                    selection = selection,
                    onClick = { onEpisodeClick(id) },
                    onPlay = { viewModel.playOrPause(id) },
                    onDownload = { viewModel.download(id) },
                    onRemoveDownload = { viewModel.removeDownload(id) },
                    onToggleQueue = { viewModel.toggleQueued(id) },
                )
            }
        }
    }
}

/**
 * Eine Folge im Reiter „Neu“: links ein kleines Bild neben Titel und Abo (mit Datum), darunter höchstens zwei Zeilen
 * Inhaltsangabe aus den Shownotes, unten links Download und Warteschlange, rechts Abspielen mit der Restzeit ([remainingMs],
 * beim Tempo des Abos; [PlayTimeButton]). Während der Mehrfachauswahl schaltet ein Tipp die Folge dazu oder weg, und die
 * Knöpfe ruhen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NewEpisodeRow(
    item: BookWithProgress,
    withTime: Boolean,
    remainingMs: Long?,
    download: TrackDownload,
    downloadFraction: Float?,
    queued: Boolean,
    playing: Boolean,
    selection: EpisodeSelection,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    onToggleQueue: () -> Unit,
) {
    val book = item.book
    var menu by remember { mutableStateOf(false) }
    val selecting = selection.active
    val selected = book.id in selection
    val summary = remember(book.description, book.title) { descriptionSummary(book.description, book.title) }
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                .combinedClickable(
                    onClick = { if (selecting) selection.toggle(book.id) else onClick() },
                    onLongClick = { if (selecting) selection.toggle(book.id) else menu = true },
                )
                .semantics { if (selecting) selectedSemantics = selected }
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectableArt(book.id, selected, Modifier.size(48.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(book.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (book.starred) StarredMark(Modifier.padding(end = 4.dp))
                        val source = listOfNotNull(
                            book.author.takeIf { it.isNotBlank() },
                            book.addedAt.takeIf { it > 0 }?.let { formatEpisodeDay(it, withTime) },
                        ).joinToString(" · ")
                        Text(
                            source,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (summary.isNotEmpty()) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // Die Symbole stehen bündig mit dem Bild darüber, die Tippfläche ragt in den Rand.
                Row(Modifier.offset(x = (-12).dp), verticalAlignment = Alignment.CenterVertically) {
                    // Dateien vom Gerät sind schon da.
                    if (!book.isLocal) DownloadButton(download, downloadFraction, onDownload, onRemoveDownload)
                    QueueButton(queued, enabled = !selecting, onToggleQueue)
                }
                Spacer(Modifier.weight(1f))
                PlayTimeButton(item, remainingMs, playing, enabled = !selecting, onPlay)
            }
        }
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = { selection.toggle(book.id) })
    }
}

/** Ans Ende der Warteschlange bzw. heraus; eine eingereihte Folge zeigt den Haken in der Akzentfarbe (wie ein fertiger Download). */
@Composable
private fun QueueButton(queued: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    IconButton(onClick = onToggle, enabled = enabled) {
        if (queued) {
            Icon(
                painterResource(DesignR.drawable.ic_playlist_add_check),
                contentDescription = stringResource(R.string.episode_queue_remove),
                tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        } else {
            Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = stringResource(R.string.episode_queue_add))
        }
    }
}

/**
 * Abspielen samt Restzeit als ein Knopf. Die Form zeigt den Stand: nie angefangen tonal mit der ganzen Dauer, angefangen
 * (oder gerade laufend) gefüllt mit „noch …“, zu Ende gehört nur umrandet mit „Gehört“ (ein Tipp spielt sie von vorn).
 * Ohne bekannte Dauer steht nur das Symbol da.
 */
@Composable
private fun PlayTimeButton(item: BookWithProgress, remainingMs: Long?, playing: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val progress = item.progress
    val finished = progress?.finished == true
    val started = !finished && (progress?.positionMs ?: 0) > 0
    val label = when {
        finished -> stringResource(R.string.spoken_finished)
        remainingMs == null -> null
        started -> stringResource(R.string.book_remaining, formatDuration(remainingMs))
        else -> formatDuration(remainingMs)
    }
    val content: @Composable RowScope.() -> Unit = {
        Icon(
            painterResource(if (playing) DesignR.drawable.ic_pause else DesignR.drawable.ic_play),
            contentDescription = stringResource(if (playing) R.string.action_pause else R.string.podcast_play_episode),
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        if (label != null) {
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(label, maxLines = 1)
        }
    }
    val padding = ButtonDefaults.ButtonWithIconContentPadding
    when {
        started || playing -> Button(onClick = onClick, enabled = enabled, contentPadding = padding, content = content)
        finished -> OutlinedButton(onClick = onClick, enabled = enabled, contentPadding = padding, content = content)
        else -> FilledTonalButton(onClick = onClick, enabled = enabled, contentPadding = padding, content = content)
    }
}
