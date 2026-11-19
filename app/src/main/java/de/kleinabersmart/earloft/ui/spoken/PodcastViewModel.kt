package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.model.FeedSort
import de.kleinabersmart.earloft.core.model.sortedFeeds
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastViewModel @Inject constructor(
    podcasts: PodcastRepository,
    private val refresher: PodcastRefresher,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    continueListening: ContinueListening,
    private val settings: PodcastSettings,
) : ViewModel() {

    /** Just a row above the subscriptions; without its own hint, it simply disappears on error. */
    val continueListening: StateFlow<List<BookWithProgress>> = continueListening.episodes
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** "Recently added" on start; like "Continue listening" without its own hint on error. */
    val recent: StateFlow<List<BookWithProgress>> = podcasts.recentEpisodes()
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How the subscriptions are ordered (start and "Subscriptions" tab). */
    val feedSort: StateFlow<FeedSort> = settings.feedSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedSort.NAME)

    private val feedStats = podcasts.feedStats().orOnError(emptyMap())

    /** Number of new episodes per subscription for the tiles. */
    val newEpisodeCounts: StateFlow<Map<String, Int>> = feedStats
        .map { stats -> stats.mapValues { it.value.newEpisodes } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val feeds: StateFlow<LoadState<List<PodcastFeed>>> = combine(podcasts.feeds, settings.feedSort, feedStats) { feeds, sort, stats ->
        feeds.sortedFeeds(sort, stats)
    }
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun setFeedSort(sort: FeedSort) {
        viewModelScope.launch { settings.setFeedSort(sort) }
    }

    fun refreshAll() = refresher.refreshAll()

    /** The episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * If the episode is playing, it pauses; if it is loaded and paused, it continues; otherwise it starts or resumes
     * (and is then at the very front of "Continue listening").
     */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }
}
