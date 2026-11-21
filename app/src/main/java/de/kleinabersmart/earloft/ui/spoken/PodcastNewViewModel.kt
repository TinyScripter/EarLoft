package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.core.model.filterEpisodes
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** "New" tab of the podcast library: the newest episodes of all subscriptions with download and queue per row. */
@HiltViewModel
class PodcastNewViewModel @Inject constructor(
    podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val downloads: EpisodeDownloads,
    downloadProgress: EpisodeDownloadProgress,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = podcasts.latestEpisodes()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val downloadStates: StateFlow<Map<Long, TrackDownload>> = podcasts.episodeDownloadStates()
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val progress: StateFlow<Map<Long, Float>> = downloadProgress.fractions

    /** Speed per subscription (otherwise the podcasts default) for the remaining time, as on the queue page. */
    val feedSpeeds: StateFlow<Map<String, Float>> = combine(podcasts.feeds, spokenSettings.podcastEffects) { feeds, area ->
        feeds.associate { it.id to (it.effects.speed ?: area.speed) }
    }
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Episodes currently in "Continue listening" (the queue), including those that got there automatically. */
    val queued: StateFlow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .orOnError(emptySet())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _filter = MutableStateFlow(EpisodeFilter.ALL)

    /** The chip above the list, as on the subscription page; only for this screen, not saved. */
    val filter: StateFlow<EpisodeFilter> = _filter.asStateFlow()

    fun setFilter(filter: EpisodeFilter) {
        _filter.value = filter
    }

    /** [episodes] after [filter] (what the list and "Select all" show). */
    val shown: StateFlow<LoadState<List<BookWithProgress>>> = combine(episodes, filter, downloadStates) { state, filter, downloads ->
        if (state is LoadState.Ready) {
            val downloaded = downloads.filterValues { it == TrackDownload.DONE }.keys
            LoadState.Ready(state.value.filterEpisodes(filter, oldestFirst = false, downloaded = downloaded))
        } else {
            state
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** The episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Messages as on the subscription page, e.g. that a download is waiting for Wi-Fi. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** Like [PodcastViewModel.playOrPause]. */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }

    /** To the end of the queue or out of it, as in the episode menu. */
    fun toggleQueued(bookId: Long) {
        val inQueue = bookId in queued.value
        viewModelScope.launch { if (inQueue) continueListening.remove(bookId) else continueListening.append(bookId) }
    }

    fun download(bookId: Long) {
        viewModelScope.launch {
            when (downloads.download(bookId)) {
                DownloadStart.OFFLINE -> _messages.emit(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI -> _messages.emit(R.string.podcast_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }

    fun removeDownload(bookId: Long) {
        viewModelScope.launch { downloads.remove(bookId) }
    }
}
