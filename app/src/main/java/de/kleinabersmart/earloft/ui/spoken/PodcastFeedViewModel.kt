package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.EffectOverrides
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.filterEpisodes
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.SpokenEffects
import de.kleinabersmart.earloft.core.data.spoken.SpokenEffectsRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.asLoadStateOrMissing
import de.kleinabersmart.earloft.ui.orOnError
import de.kleinabersmart.earloft.ui.valueOrNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = PodcastFeedViewModel.Factory::class)
class PodcastFeedViewModel @AssistedInject constructor(
    @Assisted private val feedId: String,
    private val podcasts: PodcastRepository,
    private val rows: EpisodeRowActions,
    private val downloads: EpisodeDownloads,
    downloadProgress: EpisodeDownloadProgress,
    private val refresher: PodcastRefresher,
    private val trialPages: TrialPodcastPages,
    private val effects: SpokenEffectsRepository,
    spokenSettings: SpokenSettings,
    podcastSettings: PodcastSettings,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    init {
        // As long as a page of the podcast is open, a trial subscription persists (concept 12.10).
        trialPages.opened(feedId)
    }

    override fun onCleared() {
        trialPages.closed(feedId)
    }

    val refreshing: StateFlow<Boolean> = refresher.refreshing

    fun refresh() = refresher.refreshFeed(feedId)

    /** The episode currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = rows.playingBookId
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Fraction of the episodes currently downloading (0..1, [EpisodeDownloadProgress.UNKNOWN] without known size). */
    val progress: StateFlow<Map<Long, Float>> = downloadProgress.fractions

    /** [LoadState.Missing] if the subscription was ended or the trial subscription was cleaned up while the page was in the back stack. */
    val feed: StateFlow<LoadState<PodcastFeed>> = podcasts.feed(feedId)
        .asLoadStateOrMissing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = podcasts.episodes(feedId)
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Episodes currently in "Continue listening" (the queue), including those that got there automatically. */
    val queued: StateFlow<Set<Long>> = rows.queued
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** To the end of the queue or out of it, as in the episode menu. */
    fun toggleQueued(bookId: Long) {
        val inQueue = bookId in queued.value
        viewModelScope.launch { rows.setQueued(bookId, !inQueue) }
    }

    private val searchQuery = MutableStateFlow("")

    /** Search text of the [PodcastSearchScreen] page; the results come via [searchHits]. */
    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    /**
     * Matches in title and summary. The search text per episode is normalized once per change of the episodes instead of
     * on every keystroke; filtering happens off the main thread and only once typing pauses briefly.
     */
    @OptIn(FlowPreview::class)
    val searchHits: StateFlow<EpisodeHits?> = combine(
        episodes.mapNotNull { state -> state.valueOrNull?.map(::SearchEntry) },
        searchQuery.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
    ) { index, query ->
        val needle = query.normalizeForMerge()
        EpisodeHits(query, if (needle.isEmpty()) emptyList() else index.filter { it.matches(needle) }.map { it.episode })
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Download state per episode. */
    val downloadStates: StateFlow<Map<Long, TrackDownload>> = podcasts.downloadStates(feedId)
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The episodes by the subscription's filter and order (chips and sorting on its page). */
    val shownEpisodes: StateFlow<LoadState<List<BookWithProgress>>> = combine(
        episodes,
        podcasts.feed(feedId),
        downloadStates,
    ) { state, feed, downloads ->
        val list = state.valueOrNull ?: return@combine state
        val done = downloads.filterValues { it == TrackDownload.DONE }.keys
        LoadState.Ready(list.filterEpisodes(feed?.episodeFilter ?: EpisodeFilter.ALL, feed?.listOldestFirst == true, done))
    }
        // The second query of the feed had no error handling: a database error ended the app (review2 J49).
        .orOnError(LoadState.Failed)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun setEpisodeFilter(filter: EpisodeFilter) {
        val feed = feed.value.valueOrNull ?: return
        viewModelScope.launch { podcasts.setEpisodeList(feedId, filter, feed.listOldestFirst) }
    }

    fun setListOldestFirst(oldestFirst: Boolean) {
        val feed = feed.value.valueOrNull ?: return
        viewModelScope.launch { podcasts.setEpisodeList(feedId, feed.episodeFilter, oldestFirst) }
    }

    fun setQueueMode(mode: FeedQueueMode) {
        viewModelScope.launch { podcasts.setQueueMode(feedId, mode) }
    }

    fun setKeepEpisodes(keep: Int) {
        viewModelScope.launch { podcasts.setKeepEpisodes(feedId, keep) }
    }

    /** Default from Settings → Podcasts, for "Default (…)". */
    val defaultAutoDownloadCount: StateFlow<Int> = podcastSettings.autoDownloadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSettings.DEFAULT_AUTO_DOWNLOAD_COUNT)

    /** Swipe gestures on the episode rows (Settings → Podcasts → Episode lists). */
    val swipeActions: StateFlow<Boolean> = podcastSettings.swipeActions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** `null` = default. A larger count makes the automation catch up immediately ([EpisodeDownloads]). */
    fun setAutoDownloadCount(count: Int?) {
        viewModelScope.launch { podcasts.setAutoDownloadCount(feedId, count) }
    }

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Messages for the user (string resource), e.g. that a download is waiting for a connection. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    /** On a trial subscription the episode also goes into the queue ([EpisodeRowActions.download]). */
    fun download(bookId: Long) {
        viewModelScope.launch { rows.download(bookId)?.let { _messages.emit(it) } }
    }

    fun removeDownload(bookId: Long) {
        viewModelScope.launch { rows.removeDownload(bookId) }
    }

    fun removeHeardDownloads() {
        viewModelScope.launch { downloads.removeHeard(feedId) }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { podcasts.setAutoDownload(feedId, enabled) }
    }

    fun setNewestFirst(enabled: Boolean) {
        viewModelScope.launch { podcasts.setNewestFirst(feedId, enabled) }
    }

    fun setContinuous(enabled: Boolean) {
        viewModelScope.launch { podcasts.setContinuous(feedId, enabled) }
    }

    fun setNotifyNew(enabled: Boolean) {
        viewModelScope.launch { podcasts.setNotifyNew(feedId, enabled) }
    }

    /** The area's default, for "Default (…)" in the subscription settings. */
    val areaEffects: StateFlow<SpokenEffects> = spokenSettings.podcastEffects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpokenEffects.DEFAULT)

    /** Speed and effects of the subscription; `null` = the area's default. Episodes with their own values keep them. */
    fun setEffects(change: (EffectOverrides) -> EffectOverrides) {
        viewModelScope.launch { effects.setFeedEffects(feedId, change) }
    }

    fun setSkipIntroOutro(introS: Int, outroS: Int) {
        viewModelScope.launch { effects.setSkipIntroOutro(feedId, introS, outroS) }
    }

    /** Deletes the downloads first (otherwise the files would remain), then the subscription; in the app scope like [archive]. */
    fun unsubscribe() {
        applicationScope.launch {
            downloads.removeAllOf(feedId)
            podcasts.unsubscribe(feedId)
        }
    }

    /** Archives the subscription and deletes its downloads; in the app scope, because the page closes immediately. */
    fun archive() {
        applicationScope.launch {
            podcasts.archive(feedId)
            downloads.removeAllOf(feedId)
        }
    }

    /** User name of the stored login of a protected feed; `null` = none (concept 12.10). */
    val credentialsUser: StateFlow<String?> = podcasts.credentials(feedId)
        .map { it?.username }
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** If the protected feed moved to another host, its address: the dialog names it instead of the old one. */
    val credentialsAddress: StateFlow<String?> = podcasts.credentialsAddress(feedId)
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Stores the credentials (`null` removes them) and syncs right away; in the app scope so it is stored even after the page
     * is closed. The message says whether the feed accepted them.
     */
    fun setCredentials(credentials: PodcastRepository.Credentials?) {
        applicationScope.launch {
            val refreshed = podcasts.setCredentials(feedId, credentials) != null
            if (credentials != null) _messages.emit(if (refreshed) R.string.podcast_credentials_saved else R.string.podcast_credentials_failed)
        }
    }

    /**
     * The trial subscription becomes a subscription; episodes, positions and downloads stay. The setup wizard closes the page
     * right away, so this runs in the app scope and counts as an open page until stored: otherwise the trial cleanup could
     * remove the feed first (review H12).
     */
    fun subscribe() {
        trialPages.opened(feedId)
        applicationScope.launch {
            try {
                podcasts.subscribeTrial(feedId)
            } finally {
                trialPages.closed(feedId)
            }
        }
    }

    /** Like [PodcastViewModel.playOrPause]. */
    fun playOrPause(bookId: Long) = rows.playOrPause(bookId)

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): PodcastFeedViewModel
    }
}

/** Results of the episode search; [query] is the search text they belong to. */
data class EpisodeHits(val query: String, val hits: List<BookWithProgress>)

/**
 * An episode with normalized title and description, with [withPodcast] also the podcast's name (queue);
 * separated so that no match spans the boundary between fields.
 */
internal class SearchEntry(val episode: BookWithProgress, withPodcast: Boolean = false) {
    private val title = episode.book.title.normalizeForMerge()
    private val description = descriptionForSearch(episode.book.description).normalizeForMerge()
    private val podcast = if (withPodcast) episode.book.author.normalizeForMerge() else ""

    fun matches(needle: String): Boolean = needle in title || needle in description || needle in podcast
}

internal const val SEARCH_DEBOUNCE_MS = 150L
