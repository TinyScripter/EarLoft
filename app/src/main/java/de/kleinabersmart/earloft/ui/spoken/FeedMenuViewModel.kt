package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.firstUnheard
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What a subscription's menu currently offers. */
data class FeedMenuState(
    /** The next unplayed episode in the subscription's playback order; `null` if all are played. */
    val next: BookWithProgress?,
    /** [next] is already in "Continue listening". */
    val nextQueued: Boolean,
    /** Episodes not yet fully played (for "Mark all as played"). */
    val unheard: Int,
    /** There are downloaded episodes that have been fully played. */
    val hasHeardDownloads: Boolean,
)

/** For the [FeedMenu]; as with the album menu, the query happens only on opening, not per tile. */
@HiltViewModel
class FeedMenuViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val downloads: EpisodeDownloads,
    private val refresher: PodcastRefresher,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
) : ViewModel() {

    fun state(feedId: String): Flow<FeedMenuState> = combine(
        podcasts.feed(feedId),
        podcasts.episodes(feedId),
        podcasts.downloadStates(feedId),
        continueListening.episodes,
    ) { feed, episodes, downloadStates, queue ->
        val next = episodes.firstUnheard(feed?.newestFirst ?: false)
        FeedMenuState(
            next = next,
            nextQueued = next != null && queue.any { it.book.id == next.book.id },
            unheard = episodes.count { it.progress?.finished != true },
            hasHeardDownloads = episodes.any { it.progress?.finished == true && downloadStates[it.book.id] == TrackDownload.DONE },
        )
    }

    /** Like [PodcastViewModel.playOrPause], just without pause: if the episode is already loaded, it continues there. */
    fun play(bookId: Long) {
        val state = playback.state.value
        when {
            state.spoken?.bookId != bookId -> launcher.play(bookId)
            !state.isPlaying -> playback.togglePlayPause()
        }
    }

    /** Appends the episode to the end of "Continue listening". */
    fun queue(bookId: Long) {
        viewModelScope.launch { continueListening.append(bookId) }
    }

    fun refresh(feedId: String) = refresher.refreshFeed(feedId)

    fun markAllPlayed(feedId: String) {
        viewModelScope.launch { podcasts.setAllPlayed(feedId) }
    }

    fun removeHeardDownloads(feedId: String) {
        viewModelScope.launch { downloads.removeHeard(feedId) }
    }
}
