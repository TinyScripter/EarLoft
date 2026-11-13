package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.FeedEpisodes
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

    /** Nur eine Zeile über den Abos; ohne eigenen Hinweis, fällt sie bei einem Fehler einfach weg. */
    val continueListening: StateFlow<List<BookWithProgress>> = continueListening.episodes
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** „Zuletzt hinzugefügt“ auf Start; wie „Weiterhören“ ohne eigenen Hinweis bei einem Fehler. */
    val recent: StateFlow<List<BookWithProgress>> = podcasts.recentEpisodes()
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pro Abo die noch nicht zu Ende gehörten Folgen. */
    val unheard: StateFlow<LoadState<List<FeedEpisodes>>> = podcasts.unheardByFeed()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Wie die Abos geordnet sind (Start und Reiter „Abos“). */
    val feedSort: StateFlow<FeedSort> = settings.feedSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FeedSort.NAME)

    private val feedStats = podcasts.feedStats().orOnError(emptyMap())

    /** Zahl der neuen Folgen je Abo für die Kacheln. */
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

    /** Die Folge, die gerade läuft (nicht: pausiert). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Läuft die Folge, pausiert sie; ist sie geladen und pausiert, geht es weiter; sonst startet oder setzt sie fort
     * (und steht danach ganz vorn in „Weiterhören“).
     */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }
}
