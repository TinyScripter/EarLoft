package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookDownload
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.model.OfflineMark
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.components.OfflineBadge
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BookDownloadStatesViewModel @Inject constructor(books: BookRepository, podcasts: PodcastRepository) : ViewModel() {
    /** Books and episodes; for episodes without size (nobody needs it here). */
    val states: StateFlow<Map<Long, BookDownload>> = combine(books.downloads(), podcasts.episodeDownloadStates()) { books, episodes ->
        books + episodes.mapValues { (_, state) -> BookDownload(state, 0) }
    }
        // At the app's root: a database error must not end the app, the badges just stay away (review4 T46).
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedCounts: StateFlow<Map<String, Int>> = podcasts.downloadedEpisodeCounts()
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** Download state of books and episodes for all tiles and lists; without an entry, [TrackDownload.NONE] applies. */
val LocalBookDownloads: ProvidableCompositionLocal<Map<Long, BookDownload>> = compositionLocalOf { emptyMap() }

/** Number of downloaded episodes per podcast (subscription tiles); without an entry, none. */
val LocalFeedDownloads: ProvidableCompositionLocal<Map<String, Int>> = compositionLocalOf { emptyMap() }

@Composable
fun rememberBookDownloads(viewModel: BookDownloadStatesViewModel = hiltViewModel()): Map<Long, BookDownload> {
    val states by viewModel.states.collectAsStateWithLifecycle()
    return states
}

@Composable
fun rememberFeedDownloads(viewModel: BookDownloadStatesViewModel = hiltViewModel()): Map<String, Int> {
    val counts by viewModel.feedCounts.collectAsStateWithLifecycle()
    return counts
}

/** Marker of a book or episode; removed and never-downloaded ones have none. */
val TrackDownload.offlineMark: OfflineMark?
    get() = when (this) {
        TrackDownload.DONE -> OfflineMark.DONE
        TrackDownload.QUEUED, TrackDownload.RUNNING -> OfflineMark.PENDING
        TrackDownload.FAILED -> OfflineMark.FAILED
        TrackDownload.NONE, TrackDownload.REMOVED -> null
    }

/** Marker of a book: like [TrackDownload.offlineMark], partially downloaded ones like albums. */
val BookDownload.offlineMark: OfflineMark?
    get() = if (partial) OfflineMark.PARTIAL else state.offlineMark

/** [OfflineBadge] of a book or episode, to be placed at the bottom left of the cover. */
@Composable
fun BookDownloadBadge(bookId: Long, modifier: Modifier = Modifier) {
    OfflineBadge(LocalBookDownloads.current[bookId]?.offlineMark, modifier)
}

/** [OfflineBadge] of a subscription with the number of downloaded episodes. */
@Composable
fun FeedDownloadBadge(feedId: String, modifier: Modifier = Modifier) {
    val count = LocalFeedDownloads.current[feedId] ?: return
    OfflineBadge(OfflineMark.DONE, modifier, count = count)
}
