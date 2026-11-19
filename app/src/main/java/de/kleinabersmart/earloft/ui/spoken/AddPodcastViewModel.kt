package de.kleinabersmart.earloft.ui.spoken

import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.TrialPodcastPages
import de.kleinabersmart.earloft.core.data.podcast.OpmlImportResult
import de.kleinabersmart.earloft.core.data.podcast.PodcastDirectorySearch
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.podcast.SubscribeResult
import de.kleinabersmart.earloft.core.network.podcast.directory.DirectoryPodcast
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastCategory
import de.kleinabersmart.earloft.core.network.podcast.directory.PodcastDirectory
import de.kleinabersmart.earloft.core.network.podcast.directory.feedKey
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State while subscribing (feed address or preview). */
sealed interface AddPodcastState {
    data object Idle : AddPodcastState
    data object Working : AddPodcastState
    data class Failed(@param:StringRes val message: Int) : AddPodcastState
    data class Done(val feedId: String) : AddPodcastState

    /** The subscription already exists but is archived: the user decides whether it is reactivated. */
    data class Archived(val feedId: String, val title: String) : AddPodcastState

    /**
     * The feed at [input] requires authentication (concept 12.10 "protected feeds"); [rejected]: the entered credentials
     * were not accepted ([username] was their user name).
     */
    data class NeedsCredentials(val input: String, val rejected: Boolean, val username: String = "") : AddPodcastState
}

/** Success: the new subscription; already subscribed: hint; protected: ask for credentials; otherwise the error message. */
private fun SubscribeResult.toState(input: String, username: String?): AddPodcastState = when (this) {
    is SubscribeResult.Added -> AddPodcastState.Done(feedId)
    is SubscribeResult.AlreadySubscribed -> AddPodcastState.Failed(R.string.podcast_already)
    is SubscribeResult.Archived -> AddPodcastState.Archived(feedId, title)
    is SubscribeResult.CredentialsNeeded -> AddPodcastState.NeedsCredentials(input, rejected, username.orEmpty())
    else -> AddPodcastState.Failed(message)
}

@get:StringRes
internal val SubscribeResult.message: Int
    get() = when (this) {
        is SubscribeResult.Added, is SubscribeResult.AlreadySubscribed, is SubscribeResult.Archived -> R.string.podcast_already
        SubscribeResult.InvalidUrl -> R.string.podcast_error_invalid
        SubscribeResult.Unreachable -> R.string.podcast_error_unreachable
        SubscribeResult.NotAFeed -> R.string.podcast_error_not_feed
        is SubscribeResult.CredentialsNeeded -> R.string.podcast_error_credentials
    }

/**
 * Subscribes in the app scope: leaving the page during "Reading feed …" only cancels the waiting, the subscription
 * is still created (a large feed takes a few seconds).
 */
private suspend fun subscribeOutlivingScreen(
    scope: CoroutineScope,
    podcasts: PodcastRepository,
    input: String,
    credentials: PodcastRepository.Credentials?,
): SubscribeResult =
    scope.async { podcasts.subscribe(input, credentials) }.await()

/** Search in the directories; [Done] belongs to [term]. */
sealed interface DirectorySearchState {
    data object Idle : DirectorySearchState
    data object Searching : DirectorySearchState
    data class Done(val term: String, val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>) : DirectorySearchState
}

/** Popular podcasts without a search term ("Discover"); [Done] belongs to [category] (`null` = all). */
sealed interface DiscoverState {
    val category: PodcastCategory?

    data class Loading(override val category: PodcastCategory?) : DiscoverState
    data class Done(override val category: PodcastCategory?, val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>) : DiscoverState
}

/** "Add podcast" page: feed address, OPML import and search in directories (concept 12.10). */
@HiltViewModel
class AddPodcastViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val directorySearch: PodcastDirectorySearch,
    private val podcastSettings: PodcastSettings,
    private val opmlImporter: OpmlImporter,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _add = MutableStateFlow<AddPodcastState>(AddPodcastState.Idle)
    val add: StateFlow<AddPodcastState> = _add

    /** [credentials]: for a protected feed, after [AddPodcastState.NeedsCredentials]. */
    fun subscribe(input: String, credentials: PodcastRepository.Credentials? = null) {
        if (_add.value == AddPodcastState.Working) return
        _add.value = AddPodcastState.Working
        viewModelScope.launch {
            _add.value = subscribeOutlivingScreen(applicationScope, podcasts, input, credentials).toState(input, credentials?.username)
        }
    }

    fun resetAdd() {
        _add.value = AddPodcastState.Idle
    }

    /** Brings back the archived subscription and then opens it (like a new one). */
    fun reactivate(feedId: String) {
        _add.value = AddPodcastState.Working
        viewModelScope.launch {
            applicationScope.async { podcasts.reactivate(feedId) }.await()
            _add.value = AddPodcastState.Done(feedId)
        }
    }

    /** Enabled directories; `null` as long as the setting hasn't been read yet. */
    val directories: StateFlow<List<PodcastDirectory>?> = directorySearch.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Directories of this build, for the dialog next to "Search in directories". */
    val availableDirectories: List<PodcastDirectory> get() = directorySearch.available

    /**
     * Turns [directory] on or off (the same setting as Settings → Podcasts). Search results apply to the
     * previous directories and are therefore searched again, including one still running (review V21); "Discover" reloads
     * the page by itself.
     */
    fun setDirectory(directory: PodcastDirectory, enabled: Boolean) {
        viewModelScope.launch {
            podcastSettings.setDirectory(directory, enabled)
            if (_search.value != DirectorySearchState.Idle) lastTerm?.let(::search)
        }
    }

    /** Subscriptions by [feedKey], to mark results as already subscribed. */
    val subscribed: StateFlow<Map<String, String>> = podcasts.subscribedByKey
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _search = MutableStateFlow<DirectorySearchState>(DirectorySearchState.Idle)
    val search: StateFlow<DirectorySearchState> = _search

    private var searchJob: Job? = null

    /** Term of the last search, also while it is running. */
    private var lastTerm: String? = null

    /** Searches [term] in the enabled directories; a running search is cancelled. */
    fun search(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        lastTerm = trimmed
        searchJob?.cancel()
        _search.value = DirectorySearchState.Searching
        searchJob = viewModelScope.launch {
            val result = directorySearch.search(trimmed)
            _search.value = DirectorySearchState.Done(trimmed, result.hits, result.failed)
        }
    }

    /** The search field is empty: back to "Discover" instead of the results. */
    fun clearSearch() {
        searchJob?.cancel()
        _search.value = DirectorySearchState.Idle
    }

    /** Enabled directories with charts or trends; `null` as long as the setting hasn't been read yet. */
    val discoverable: StateFlow<List<PodcastDirectory>?> = directorySearch.discoverable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Country of the Apple charts, for the heading. */
    val country: StateFlow<String?> = directorySearch.country
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _discover = MutableStateFlow<DiscoverState?>(null)

    /** `null`: not loaded yet. */
    val discover: StateFlow<DiscoverState?> = _discover

    private var discoverJob: Job? = null

    /** Loaded charts per category and directories while the page lives: switching back and forth doesn't query again. */
    private val popular = HashMap<Pair<PodcastCategory?, List<PodcastDirectory>>, DiscoverState.Done>()

    /**
     * Loads the popular podcasts of [directories] for [category] (a running query is cancelled). A result with a
     * failed directory isn't remembered, so that another tap tries it again.
     */
    fun discover(category: PodcastCategory?, directories: List<PodcastDirectory>) {
        if (directories.isEmpty()) return
        val key = category to directories
        popular[key]?.let {
            discoverJob?.cancel()
            _discover.value = it
            return
        }
        if (_discover.value == DiscoverState.Loading(category) && discoverJob?.isActive == true) return
        discoverJob?.cancel()
        _discover.value = DiscoverState.Loading(category)
        discoverJob = viewModelScope.launch {
            val result = directorySearch.popular(category)
            val done = DiscoverState.Done(category, result.hits, result.failed)
            if (result.failed.isEmpty()) popular[key] = done
            _discover.value = done
        }
    }

    private val _opmlResults = Channel<OpmlImportResult?>(Channel.BUFFERED)

    /** Result of an OPML import, to be shown once; `null` = file not readable. */
    val opmlResults: Flow<OpmlImportResult?> = _opmlResults.receiveAsFlow()

    /** In the app scope as in the settings: otherwise "Back" during the import would abort in the middle of deleting or creating (review V27). */
    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch { _opmlResults.send(applicationScope.async { opmlImporter.import(uri, replace) }.await()) }
    }
}

/** Loading the feed for the preview. */
sealed interface PreviewState {
    data object Loading : PreviewState

    /** Created as a trial subscription (or already present): continue on its page. */
    data class Opened(val feedId: String) : PreviewState

    /** Already subscribed but archived: reactivate only after confirmation. */
    data class Archived(val feedId: String, val title: String) : PreviewState
    data class Failed(@param:StringRes val message: Int) : PreviewState
}

/** Preview of a result (concept 12.10): creates the feed as a trial subscription so its episodes can be played and downloaded. */
@HiltViewModel(assistedFactory = PodcastPreviewViewModel.Factory::class)
class PodcastPreviewViewModel @AssistedInject constructor(
    @Assisted("feedUrl") private val feedUrl: String,
    @Assisted("imageUrl") private val imageUrl: String?,
    private val podcasts: PodcastRepository,
    private val trialPages: TrialPodcastPages,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val state: StateFlow<PreviewState> = _state

    /** The opened trial subscription; it counts as open until the podcast's page has taken over (otherwise the cleanup would remove it). */
    private var openedFeedId: String? = null

    init {
        load()
    }

    fun load() {
        _state.value = PreviewState.Loading
        viewModelScope.launch {
            // In the app scope: a large feed takes a few seconds; going back earlier only cancels the waiting.
            val result = applicationScope.async { podcasts.openPreview(feedUrl, imageUrl) }.await()
            _state.value = when (result) {
                is SubscribeResult.Added -> opened(result.feedId)
                is SubscribeResult.AlreadySubscribed -> opened(result.feedId)
                is SubscribeResult.Archived -> PreviewState.Archived(result.feedId, result.title)
                else -> PreviewState.Failed(result.message)
            }
        }
    }

    /** Reactivates the archived subscription and then hands over to its page. */
    fun reactivate(feedId: String) {
        _state.value = PreviewState.Loading
        viewModelScope.launch {
            applicationScope.async { podcasts.reactivate(feedId) }.await()
            _state.value = opened(feedId)
        }
    }

    private fun opened(feedId: String): PreviewState {
        if (openedFeedId == null) {
            openedFeedId = feedId
            trialPages.opened(feedId)
        }
        return PreviewState.Opened(feedId)
    }

    override fun onCleared() {
        openedFeedId?.let(trialPages::closed)
    }

    @AssistedFactory
    interface Factory {
        fun create(@Assisted("feedUrl") feedUrl: String, @Assisted("imageUrl") imageUrl: String?): PodcastPreviewViewModel
    }
}
