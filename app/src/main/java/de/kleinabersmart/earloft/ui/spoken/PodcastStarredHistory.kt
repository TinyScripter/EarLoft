package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.EpisodeHistoryEntry
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.flow.map
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Reiter „Gemerkt“ der Podcast-Bibliothek: alle gemerkten Folgen, zuletzt gemerkte zuerst, als [EpisodeSummaryRow] wie im
 * Reiter „Neu“ (mit dem Podcast in der zweiten Zeile und Mehrfachauswahl über [selection]). Ohne Wischen auf den Zeilen: Der Reiter sitzt im Pager der
 * Bibliothek, und waagerechtes Wischen wechselt dort den Reiter (sonst nahm die Zeile die Geste, Review Abschnitt 5).
 */
@Composable
fun StarredEpisodes(
    onEpisodeClick: (Long) -> Unit,
    selection: EpisodeSelection,
    viewModel: PodcastStarredHistoryViewModel = hiltViewModel(),
) {
    val starred by viewModel.starred.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val speeds by viewModel.feedSpeeds.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }
    LoadableList(starred, stringResource(R.string.podcast_starred_empty)) { list ->
        val sameDay = episodesSharingDay(list.map { it.book })
        LazyColumn(Modifier.fillMaxSize().readableWidth(TwoColumnMinWidth), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            groupedItems(list, key = { it.book.id }) { item ->
                val id = item.book.id
                EpisodeSummaryRow(
                    item,
                    withPodcast = true,
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

/** Schlüssel einer Zeile: Folgen, die es nicht mehr gibt, haben keine `bookId`. */
private val EpisodeHistoryEntry.key: String get() = "$feedId|$bookId|$podcast|$title|$lastListenedAt"

/** So viele zuletzt gehörte Folgen zeigt die Statistik; der Rest steht auf der eigenen Seite „Zuletzt gehört“. */
private const val HISTORY_PREVIEW = 3

/**
 * Abschnitt „Zuletzt gehört“ im Reiter „Statistik“ (unter dem Diagramm „Verlauf“): die letzten [HISTORY_PREVIEW] Folgen und
 * darunter „Alle zeigen“; ohne gehörte Folgen nichts.
 */
internal fun LazyListScope.recentHistory(
    history: List<EpisodeHistoryEntry>,
    onEpisodeClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    onOpenHistory: () -> Unit,
) {
    if (history.isEmpty()) return
    item(key = "recent-history") { SectionHeader(stringResource(R.string.podcast_history)) }
    val rows: List<EpisodeHistoryEntry?> = history.take(HISTORY_PREVIEW) + null
    groupedItems(rows, key = { it?.let { entry -> "history-${entry.key}" } ?: "history-all" }) { entry ->
        if (entry != null) {
            HistoryRow(entry, onEpisodeClick, onFeedClick)
        } else {
            ListItem(
                headlineContent = { Text(stringResource(R.string.podcast_history_all)) },
                leadingContent = { Icon(painterResource(DesignR.drawable.ic_history), contentDescription = null) },
                modifier = Modifier.clickable(onClick = onOpenHistory),
            )
        }
    }
}

/**
 * Seite „Zuletzt gehört“ (der Verlauf gehörter Folgen): die zuletzt gehörten Folgen, neueste zuerst, auch solche, die nicht mehr im Feed stehen oder deren Abo
 * beendet ist (dann ohne Tipp).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastHistoryScreen(
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastStarredHistoryViewModel = hiltViewModel(),
) {
    val history by viewModel.history.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        TopAppBar(title = { Text(stringResource(R.string.podcast_history)) }, navigationIcon = { BackButton(onBack) })
        LoadableList(history, stringResource(R.string.podcast_history_empty)) { list ->
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                groupedItems(list, key = { it.key }) { entry ->
                    HistoryRow(entry, onEpisodeClick, onFeedClick)
                }
            }
        }
    }
}

/**
 * Eine gehörte Folge: Bild, Titel, Podcast, wann zuletzt gehört und wie lange. Ein Tipp öffnet die Folge; gibt es sie nicht
 * mehr, das Abo; ist auch das beendet, nichts.
 */
@Composable
private fun HistoryRow(entry: EpisodeHistoryEntry, onEpisodeClick: (Long) -> Unit, onFeedClick: (String) -> Unit) {
    val bookId = entry.bookId
    val feedId = entry.feedId
    val onClick: (() -> Unit)? = when {
        bookId != null -> { { onEpisodeClick(bookId) } }
        feedId != null -> { { onFeedClick(feedId) } }
        else -> null
    }
    ListItem(
        headlineContent = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(entry.podcast, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val facts = listOfNotNull(
                    formatEpisodeDay(entry.lastListenedAt, withTime = true),
                    formatListened(entry.listenedMs),
                    stringResource(R.string.spoken_finished).takeIf { entry.finished },
                    when {
                        feedId == null -> stringResource(R.string.podcast_stats_unsubscribed)
                        bookId == null -> stringResource(R.string.podcast_history_gone)
                        else -> null
                    },
                )
                Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            }
        },
        leadingContent = {
            if (bookId != null) BookArt(bookId, Modifier.size(56.dp)) else PodcastArt(entry.imageUrl, Modifier.size(56.dp))
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}
