package keinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.podcast.FeedEpisodes
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.model.PodcastFeed
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
import keinabersmart.kleinaberlaut.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class PodcastViewModel @Inject constructor(
    podcasts: PodcastRepository,
    private val refresher: PodcastRefresher,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    continueListening: ContinueListening,
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

    val feeds: StateFlow<LoadState<List<PodcastFeed>>> = podcasts.feeds
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

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
