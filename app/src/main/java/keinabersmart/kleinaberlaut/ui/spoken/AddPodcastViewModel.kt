package keinabersmart.kleinaberlaut.ui.spoken

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.FeedPreview
import keinabersmart.kleinaberlaut.core.data.podcast.OpmlImportResult
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastDirectorySearch
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.SubscribeResult
import keinabersmart.kleinaberlaut.core.network.podcast.ParsedFeed
import keinabersmart.kleinaberlaut.core.network.podcast.directory.DirectoryPodcast
import keinabersmart.kleinaberlaut.core.network.podcast.directory.PodcastDirectory
import keinabersmart.kleinaberlaut.core.network.podcast.directory.feedKey
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Zustand beim Abonnieren (Feed-Adresse oder Vorschau). */
sealed interface AddPodcastState {
    data object Idle : AddPodcastState
    data object Working : AddPodcastState
    data class Failed(@param:StringRes val message: Int) : AddPodcastState
    data class Done(val feedId: String) : AddPodcastState
}

/** Gelungen: das neue Abo; schon abonniert: Hinweis; sonst die Fehlermeldung. */
private fun SubscribeResult.toState(): AddPodcastState = when (this) {
    is SubscribeResult.Added -> AddPodcastState.Done(feedId)
    is SubscribeResult.AlreadySubscribed -> AddPodcastState.Failed(R.string.podcast_already)
    else -> AddPodcastState.Failed(message)
}

@get:StringRes
private val SubscribeResult.message: Int
    get() = when (this) {
        is SubscribeResult.Added, is SubscribeResult.AlreadySubscribed -> R.string.podcast_already
        SubscribeResult.InvalidUrl -> R.string.podcast_error_invalid
        SubscribeResult.Unreachable -> R.string.podcast_error_unreachable
        SubscribeResult.NotAFeed -> R.string.podcast_error_not_feed
    }

/**
 * Abonniert im App-Scope: Wer die Seite während „Feed wird gelesen …“ verlässt, bricht nur das Warten ab, das Abo
 * entsteht trotzdem (ein großer Feed braucht einige Sekunden).
 */
private suspend fun subscribeOutlivingScreen(scope: CoroutineScope, podcasts: PodcastRepository, input: String): SubscribeResult =
    scope.async { podcasts.subscribe(input) }.await()

/** Suche in den Verzeichnissen; [Done] gehört zu [term]. */
sealed interface DirectorySearchState {
    data object Idle : DirectorySearchState
    data object Searching : DirectorySearchState
    data class Done(val term: String, val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>) : DirectorySearchState
}

/** Seite „Podcast hinzufügen“: Feed-Adresse, OPML-Import und Suche in Verzeichnissen (Konzept 12.10). */
@HiltViewModel
class AddPodcastViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val directorySearch: PodcastDirectorySearch,
    private val opmlImporter: OpmlImporter,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _add = MutableStateFlow<AddPodcastState>(AddPodcastState.Idle)
    val add: StateFlow<AddPodcastState> = _add

    fun subscribe(input: String) {
        if (_add.value == AddPodcastState.Working) return
        _add.value = AddPodcastState.Working
        viewModelScope.launch { _add.value = subscribeOutlivingScreen(applicationScope, podcasts, input).toState() }
    }

    fun resetAdd() {
        _add.value = AddPodcastState.Idle
    }

    /** Eingeschaltete Verzeichnisse; `null`, solange die Einstellung noch nicht gelesen ist. */
    val directories: StateFlow<List<PodcastDirectory>?> = directorySearch.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Abos nach [feedKey], um Treffer als schon abonniert zu kennzeichnen. */
    val subscribed: StateFlow<Map<String, String>> = podcasts.subscribedByKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _search = MutableStateFlow<DirectorySearchState>(DirectorySearchState.Idle)
    val search: StateFlow<DirectorySearchState> = _search

    private var searchJob: Job? = null

    /** Sucht [term] in den eingeschalteten Verzeichnissen; eine laufende Suche wird abgebrochen. */
    fun search(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        searchJob?.cancel()
        _search.value = DirectorySearchState.Searching
        searchJob = viewModelScope.launch {
            val result = directorySearch.search(trimmed)
            _search.value = DirectorySearchState.Done(trimmed, result.hits, result.failed)
        }
    }

    private val _opmlResults = Channel<OpmlImportResult?>(Channel.BUFFERED)

    /** Ergebnis eines OPML-Imports, einmalig anzuzeigen; `null` = Datei nicht lesbar. */
    val opmlResults: Flow<OpmlImportResult?> = _opmlResults.receiveAsFlow()

    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch { _opmlResults.send(opmlImporter.import(uri, replace)) }
    }
}

/** Laden des Feeds für die Vorschau. */
sealed interface PreviewState {
    data object Loading : PreviewState
    data class Loaded(val feed: ParsedFeed) : PreviewState
    data class Failed(@param:StringRes val message: Int) : PreviewState
}

/** Vorschau eines Treffers vor dem Abonnieren (Konzept 12.10). */
@HiltViewModel(assistedFactory = PodcastPreviewViewModel.Factory::class)
class PodcastPreviewViewModel @AssistedInject constructor(
    @Assisted private val feedUrl: String,
    private val podcasts: PodcastRepository,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state

    /** Id des Abos, falls der Feed schon abonniert ist. */
    val subscribedFeedId: StateFlow<String?> = podcasts.subscribedByKey
        .map { it[feedKey(feedUrl)] }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _add = MutableStateFlow<AddPodcastState>(AddPodcastState.Idle)
    val add: StateFlow<AddPodcastState> = _add

    init {
        load()
    }

    fun load() {
        _state.value = PreviewState.Loading
        viewModelScope.launch {
            _state.value = when (val preview = podcasts.preview(feedUrl)) {
                is FeedPreview.Loaded -> PreviewState.Loaded(preview.feed)
                is FeedPreview.Failed -> PreviewState.Failed(preview.result.message)
            }
        }
    }

    fun subscribe() {
        if (_add.value == AddPodcastState.Working) return
        _add.value = AddPodcastState.Working
        viewModelScope.launch {
            _add.value = when (val result = subscribeOutlivingScreen(applicationScope, podcasts, feedUrl)) {
                // Inzwischen anderswo abonniert: einfach öffnen.
                is SubscribeResult.AlreadySubscribed -> AddPodcastState.Done(result.feedId)
                else -> result.toState()
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(feedUrl: String): PodcastPreviewViewModel
    }
}
