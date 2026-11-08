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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class BookDownloadStatesViewModel @Inject constructor(books: BookRepository, podcasts: PodcastRepository) : ViewModel() {
    /** Bücher und Folgen; bei Folgen ohne Größe (die braucht hier niemand). */
    val states: StateFlow<Map<Long, BookDownload>> = combine(books.downloads(), podcasts.episodeDownloadStates()) { books, episodes ->
        books + episodes.mapValues { (_, state) -> BookDownload(state, 0) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feedCounts: StateFlow<Map<String, Int>> = podcasts.downloadedEpisodeCounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
}

/** Download-Stand der Bücher und Folgen für alle Kacheln und Listen; ohne Eintrag gilt [TrackDownload.NONE]. */
val LocalBookDownloads: ProvidableCompositionLocal<Map<Long, BookDownload>> = compositionLocalOf { emptyMap() }

/** Zahl der geladenen Folgen je Podcast (Abo-Kacheln); ohne Eintrag keine. */
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

/** Markierung eines Buchs oder einer Folge; entfernte und nie geladene haben keine. */
val TrackDownload.offlineMark: OfflineMark?
    get() = when (this) {
        TrackDownload.DONE -> OfflineMark.DONE
        TrackDownload.QUEUED, TrackDownload.RUNNING -> OfflineMark.PENDING
        TrackDownload.FAILED -> OfflineMark.FAILED
        TrackDownload.NONE, TrackDownload.REMOVED -> null
    }

/** [OfflineBadge] eines Buchs oder einer Folge, unten links auf das Cover zu setzen. */
@Composable
fun BookDownloadBadge(bookId: Long, modifier: Modifier = Modifier) {
    OfflineBadge(LocalBookDownloads.current[bookId]?.state?.offlineMark, modifier)
}

/** [OfflineBadge] eines Abos mit der Zahl der geladenen Folgen. */
@Composable
fun FeedDownloadBadge(feedId: String, modifier: Modifier = Modifier) {
    val count = LocalFeedDownloads.current[feedId] ?: return
    OfflineBadge(OfflineMark.DONE, modifier, count = count)
}
