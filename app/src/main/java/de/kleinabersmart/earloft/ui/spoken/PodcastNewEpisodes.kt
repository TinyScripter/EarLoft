package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars

/**
 * "New" tab of the podcast library: the newest episodes of all subscriptions in one list, newest at the top, including played ones
 * ([de.kleinabersmart.earloft.core.data.podcast.PodcastRepository.latestEpisodes]), as [EpisodeSummaryRow]. Multi-selection via
 * [selection], without swiping (the tab sits in the pager, like "Starred").
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
        // When a new episode arrives, it appears at the top in view instead of above the edge.
        ShowStartOnNewFirst(list.firstOrNull()?.book?.id?.toString()) { listState.scrollToItem(0) }
        val sameDay = episodesSharingDay(list.map { it.book })
        FastScrollLazyColumn(
            Modifier.fillMaxSize().readableWidth(TwoColumnMinWidth),
            state = listState,
            contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            // The list is sorted by date; the bubble names the position (#1 = newest).
            labelAt = { index -> "#${index + 1}" },
        ) {
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
