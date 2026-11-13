package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.core.common.normalizeForMerge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRefresher
import de.kleinabersmart.earloft.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.core.download.DownloadStart
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
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    private val downloads: EpisodeDownloads,
    downloadProgress: EpisodeDownloadProgress,
    private val refresher: PodcastRefresher,
    private val continueListening: ContinueListening,
    private val trialPages: TrialPodcastPages,
    private val effects: SpokenEffectsRepository,
    spokenSettings: SpokenSettings,
    podcastSettings: PodcastSettings,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    init {
        // Solange eine Seite des Podcasts offen ist, bleibt ein Probeabo bestehen (Konzept 12.10).
        trialPages.opened(feedId)
    }

    override fun onCleared() {
        trialPages.closed(feedId)
    }

    val refreshing: StateFlow<Boolean> = refresher.refreshing

    fun refresh() = refresher.refreshFeed(feedId)

    /** Die Folge, die gerade läuft (nicht: pausiert). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anteil der gerade ladenden Folgen (0..1, [EpisodeDownloadProgress.UNKNOWN] ohne bekannte Größe). */
    val progress: StateFlow<Map<Long, Float>> = downloadProgress.fractions

    /** [LoadState.Missing], wenn das Abo beendet oder das Probeabo aufgeräumt wurde, während die Seite im Verlauf lag. */
    val feed: StateFlow<LoadState<PodcastFeed>> = podcasts.feed(feedId)
        .asLoadStateOrMissing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = podcasts.episodes(feedId)
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    private val searchQuery = MutableStateFlow("")

    /** Suchtext der Seite [PodcastSearchScreen]; die Treffer kommen über [searchHits]. */
    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    /**
     * Treffer in Überschrift und Inhaltsangabe. Der Suchtext je Folge wird einmal je Änderung der Folgen normalisiert statt
     * bei jedem Tastendruck; gefiltert wird abseits des Main-Threads und erst, wenn das Tippen kurz ruht.
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

    /** Download-Zustand je Folge. */
    val downloadStates: StateFlow<Map<Long, TrackDownload>> = podcasts.downloadStates(feedId)
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Die Folgen nach Filter und Reihenfolge des Abos (Chips und Sortierung auf seiner Seite). */
    val shownEpisodes: StateFlow<LoadState<List<BookWithProgress>>> = combine(
        episodes,
        podcasts.feed(feedId),
        downloadStates,
    ) { state, feed, downloads ->
        val list = state.valueOrNull ?: return@combine state
        val done = downloads.filterValues { it == TrackDownload.DONE }.keys
        LoadState.Ready(list.filterEpisodes(feed?.episodeFilter ?: EpisodeFilter.ALL, feed?.listOldestFirst == true, done))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

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

    /** Vorgabe aus Einstellungen → Podcasts, für „Vorgabe (…)“. */
    val defaultAutoDownloadCount: StateFlow<Int> = podcastSettings.autoDownloadCount
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PodcastSettings.DEFAULT_AUTO_DOWNLOAD_COUNT)

    /** `null` = Vorgabe. Eine größere Anzahl holt die Automatik sofort nach ([EpisodeDownloads]). */
    fun setAutoDownloadCount(count: Int?) {
        viewModelScope.launch { podcasts.setAutoDownloadCount(feedId, count) }
    }

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Hinweise für den Nutzer (String-Ressource), z. B. dass ein Download auf eine Verbindung wartet. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun download(bookId: Long) {
        viewModelScope.launch {
            // Probeabo: Was geladen wird, kommt in die Warteschlange und bleibt nur, solange es dort steht.
            if (feed.value.valueOrNull?.subscribed == false) continueListening.append(bookId)
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

    /** Vorgabe des Bereichs, für „Standard (…)“ in den Abo-Einstellungen. */
    val areaEffects: StateFlow<SpokenEffects> = spokenSettings.podcastEffects
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpokenEffects.DEFAULT)

    /** Tempo und Effekte des Abos; `null` = Vorgabe des Bereichs. Folgen mit eigenen Werten behalten sie. */
    fun setEffects(change: (EffectOverrides) -> EffectOverrides) {
        viewModelScope.launch { effects.setFeedEffects(feedId, change) }
    }

    fun setSkipIntroOutro(introS: Int, outroS: Int) {
        viewModelScope.launch { effects.setSkipIntroOutro(feedId, introS, outroS) }
    }

    /** Löscht erst die Downloads (sonst blieben die Dateien liegen), dann das Abo; im App-Scope wie [archive]. */
    fun unsubscribe() {
        applicationScope.launch {
            downloads.removeAllOf(feedId)
            podcasts.unsubscribe(feedId)
        }
    }

    /** Archiviert das Abo und löscht seine Downloads; im App-Scope, denn die Seite schließt sich sofort. */
    fun archive() {
        applicationScope.launch {
            podcasts.archive(feedId)
            downloads.removeAllOf(feedId)
        }
    }

    /** Aus dem Probeabo wird ein Abo; Folgen, Stellen und Downloads bleiben. */
    fun subscribe() {
        viewModelScope.launch { podcasts.subscribeTrial(feedId) }
    }

    /** Wie [PodcastViewModel.playOrPause]. */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): PodcastFeedViewModel
    }
}

/** Treffer der Folgensuche; [query] ist der Suchtext, zu dem sie gehören. */
data class EpisodeHits(val query: String, val hits: List<BookWithProgress>)

/**
 * Eine Folge mit normalisiertem Titel und Beschreibung, mit [withPodcast] auch dem Namen des Podcasts (Warteschlange);
 * getrennt, damit kein Treffer über die Grenze der Felder reicht.
 */
internal class SearchEntry(val episode: BookWithProgress, withPodcast: Boolean = false) {
    private val title = episode.book.title.normalizeForMerge()
    private val description = descriptionForSearch(episode.book.description).normalizeForMerge()
    private val podcast = if (withPodcast) episode.book.author.normalizeForMerge() else ""

    fun matches(needle: String): Boolean = needle in title || needle in description || needle in podcast
}

internal const val SEARCH_DEBOUNCE_MS = 150L
