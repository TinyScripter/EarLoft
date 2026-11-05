package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import de.kleinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import de.kleinabersmart.kleinaberlaut.core.data.podcast.firstUnheard
import de.kleinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import de.kleinabersmart.kleinaberlaut.core.model.BookWithProgress
import de.kleinabersmart.kleinaberlaut.core.model.TrackDownload
import de.kleinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Was das Menü eines Abos gerade anbietet. */
data class FeedMenuState(
    /** Die nächste ungehörte Folge in der Abspielreihenfolge des Abos; `null`, wenn alle gehört sind. */
    val next: BookWithProgress?,
    /** [next] steht schon in „Weiterhören“. */
    val nextQueued: Boolean,
    /** Noch nicht zu Ende gehörte Folgen (für „Alle als gehört markieren“). */
    val unheard: Int,
    /** Es gibt geladene Folgen, die zu Ende gehört sind. */
    val hasHeardDownloads: Boolean,
)

/** Zum [FeedMenu]; wie beim Album-Menü wird erst beim Öffnen abgefragt, nicht je Kachel. */
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

    /** Wie [PodcastViewModel.playOrPause], nur ohne Pause: Ist die Folge schon geladen, geht es dort weiter. */
    fun play(bookId: Long) {
        val state = playback.state.value
        when {
            state.spoken?.bookId != bookId -> launcher.play(bookId)
            !state.isPlaying -> playback.togglePlayPause()
        }
    }

    /** Hängt die Folge ans Ende von „Weiterhören“. */
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
