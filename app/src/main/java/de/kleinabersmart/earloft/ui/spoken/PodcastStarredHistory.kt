package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.remember
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
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
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
 * "Starred" tab of the podcast library: all starred episodes, most recently starred first, as [EpisodeSummaryRow] as in the
 * "New" tab (with the podcast in the second line and multi-selection via [selection]). Without swiping on the rows: the tab sits in the
 * library's pager, and horizontal swiping switches the tab there (otherwise the row took the gesture, review section 5).
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
        val sameDay = remember(list) { episodesSharingDay(list.map { it.book }) }
        FastScrollLazyColumn(Modifier.fillMaxSize().readableWidth(TwoColumnMinWidth), contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
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

/** Key of a row: episodes that no longer exist have no `bookId`. */
private val EpisodeHistoryEntry.key: String get() = "$feedId|$bookId|$podcast|$title|$lastListenedAt"

/** This many recently played episodes are shown in the statistics; the rest is on its own "Recently played" page. */
private const val HISTORY_PREVIEW = 3

/**
 * "Recently played" section in the "Statistics" tab (below the "History" chart): the last [HISTORY_PREVIEW] episodes and
 * below them "Show all"; nothing without played episodes.
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
 * "Recently played" page (the history of played episodes): the most recently played episodes, newest first, including ones no longer in the feed or whose subscription
 * has ended (then without tap).
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
            FastScrollLazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                groupedItems(list, key = { it.key }) { entry ->
                    HistoryRow(entry, onEpisodeClick, onFeedClick)
                }
            }
        }
    }
}

/**
 * A played episode: image, title, podcast, when last played and for how long. A tap opens the episode; if it no longer
 * exists, the subscription; if that has ended too, nothing.
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
