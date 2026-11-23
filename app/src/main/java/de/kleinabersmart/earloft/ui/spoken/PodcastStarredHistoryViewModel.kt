package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.podcast.EpisodeHistoryEntry
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.TrackDownload
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

/** Starred episodes ("Starred" tab) and the recently played episodes (statistics, own page); concept 12.5. */
@HiltViewModel
class PodcastStarredHistoryViewModel @Inject constructor(
    podcasts: PodcastRepository,
    private val rows: EpisodeRowActions,
    downloadProgress: EpisodeDownloadProgress,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    val starred: StateFlow<LoadState<List<BookWithProgress>>> = podcasts.starredEpisodes()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val history: StateFlow<LoadState<List<EpisodeHistoryEntry>>> = podcasts.history()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val downloadStates: StateFlow<Map<Long, TrackDownload>> = podcasts.episodeDownloadStates()
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val progress: StateFlow<Map<Long, Float>> = downloadProgress.fractions

    /** Episodes currently in "Continue listening" (the queue), including those that got there automatically. */
    val queued: StateFlow<Set<Long>> = rows.queued
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Speed per subscription (otherwise the podcasts default) for the remaining time, as on the queue page. */
    val feedSpeeds: StateFlow<Map<String, Float>> = combine(podcasts.feeds, spokenSettings.podcastEffects) { feeds, area ->
        feeds.associate { it.id to (it.effects.speed ?: area.speed) }
    }
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** To the end of the queue or out of it, as in the episode menu. */
    fun toggleQueued(bookId: Long) {
        val inQueue = bookId in queued.value
        viewModelScope.launch { rows.setQueued(bookId, !inQueue) }
    }

    /** The episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = rows.playingBookId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Messages as on the subscription page, e.g. that a download is waiting for Wi-Fi. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** Like [PodcastViewModel.playOrPause]. */
    fun playOrPause(bookId: Long) = rows.playOrPause(bookId)

    /** History: also episodes of a trial subscription, which go into the queue first ([EpisodeRowActions.download]). */
    fun download(bookId: Long) {
        viewModelScope.launch { rows.download(bookId)?.let { _messages.emit(it) } }
    }

    fun removeDownload(bookId: Long) {
        viewModelScope.launch { rows.removeDownload(bookId) }
    }
}
