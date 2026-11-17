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

/** Zustand beim Abonnieren (Feed-Adresse oder Vorschau). */
sealed interface AddPodcastState {
    data object Idle : AddPodcastState
    data object Working : AddPodcastState
    data class Failed(@param:StringRes val message: Int) : AddPodcastState
    data class Done(val feedId: String) : AddPodcastState

    /** Das Abo besteht schon, ist aber archiviert: Der Nutzer entscheidet, ob es reaktiviert wird. */
    data class Archived(val feedId: String, val title: String) : AddPodcastState

    /**
     * Der Feed unter [input] verlangt eine Anmeldung (Konzept 12.10 „Geschützte Feeds“); [rejected]: die eingegebene nicht
     * ([username] war ihr Benutzername).
     */
    data class NeedsCredentials(val input: String, val rejected: Boolean, val username: String = "") : AddPodcastState
}

/** Gelungen: das neue Abo; schon abonniert: Hinweis; geschützt: Frage nach den Zugangsdaten; sonst die Fehlermeldung. */
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
 * Abonniert im App-Scope: Wer die Seite während „Feed wird gelesen …“ verlässt, bricht nur das Warten ab, das Abo
 * entsteht trotzdem (ein großer Feed braucht einige Sekunden).
 */
private suspend fun subscribeOutlivingScreen(
    scope: CoroutineScope,
    podcasts: PodcastRepository,
    input: String,
    credentials: PodcastRepository.Credentials?,
): SubscribeResult =
    scope.async { podcasts.subscribe(input, credentials) }.await()

/** Suche in den Verzeichnissen; [Done] gehört zu [term]. */
sealed interface DirectorySearchState {
    data object Idle : DirectorySearchState
    data object Searching : DirectorySearchState
    data class Done(val term: String, val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>) : DirectorySearchState
}

/** Beliebte Podcasts ohne Suchbegriff („Entdecken“); [Done] gehört zu [category] (`null` = alle). */
sealed interface DiscoverState {
    val category: PodcastCategory?

    data class Loading(override val category: PodcastCategory?) : DiscoverState
    data class Done(override val category: PodcastCategory?, val hits: List<DirectoryPodcast>, val failed: Set<PodcastDirectory>) : DiscoverState
}

/** Seite „Podcast hinzufügen“: Feed-Adresse, OPML-Import und Suche in Verzeichnissen (Konzept 12.10). */
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

    /** [credentials]: für einen geschützten Feed, nach [AddPodcastState.NeedsCredentials]. */
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

    /** Holt das archivierte Abo zurück und öffnet es danach (wie ein neues). */
    fun reactivate(feedId: String) {
        _add.value = AddPodcastState.Working
        viewModelScope.launch {
            applicationScope.async { podcasts.reactivate(feedId) }.await()
            _add.value = AddPodcastState.Done(feedId)
        }
    }

    /** Eingeschaltete Verzeichnisse; `null`, solange die Einstellung noch nicht gelesen ist. */
    val directories: StateFlow<List<PodcastDirectory>?> = directorySearch.enabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Verzeichnisse dieses Builds, für den Dialog neben „In Verzeichnissen suchen“. */
    val availableDirectories: List<PodcastDirectory> get() = directorySearch.available

    /**
     * Schaltet [directory] ein oder aus (dieselbe Einstellung wie Einstellungen → Podcasts). Treffer einer Suche gelten für
     * die bisherigen Verzeichnisse und werden deshalb neu gesucht, auch eine noch laufende (Review V21); „Entdecken“ lädt die
     * Seite selbst neu.
     */
    fun setDirectory(directory: PodcastDirectory, enabled: Boolean) {
        viewModelScope.launch {
            podcastSettings.setDirectory(directory, enabled)
            if (_search.value != DirectorySearchState.Idle) lastTerm?.let(::search)
        }
    }

    /** Abos nach [feedKey], um Treffer als schon abonniert zu kennzeichnen. */
    val subscribed: StateFlow<Map<String, String>> = podcasts.subscribedByKey
        .orOnError(emptyMap())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _search = MutableStateFlow<DirectorySearchState>(DirectorySearchState.Idle)
    val search: StateFlow<DirectorySearchState> = _search

    private var searchJob: Job? = null

    /** Begriff der letzten Suche, auch während sie läuft. */
    private var lastTerm: String? = null

    /** Sucht [term] in den eingeschalteten Verzeichnissen; eine laufende Suche wird abgebrochen. */
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

    /** Das Suchfeld ist leer: wieder „Entdecken“ statt der Treffer. */
    fun clearSearch() {
        searchJob?.cancel()
        _search.value = DirectorySearchState.Idle
    }

    /** Eingeschaltete Verzeichnisse mit Charts bzw. Trends; `null`, solange die Einstellung noch nicht gelesen ist. */
    val discoverable: StateFlow<List<PodcastDirectory>?> = directorySearch.discoverable
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Land der Apple-Charts für die Überschrift. */
    val country: StateFlow<String?> = directorySearch.country
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _discover = MutableStateFlow<DiscoverState?>(null)

    /** `null`: noch nicht geladen. */
    val discover: StateFlow<DiscoverState?> = _discover

    private var discoverJob: Job? = null

    /** Geladene Charts je Kategorie und Verzeichnissen, solange die Seite lebt: Hin- und Herwechseln fragt nicht erneut. */
    private val popular = HashMap<Pair<PodcastCategory?, List<PodcastDirectory>>, DiscoverState.Done>()

    /**
     * Lädt die beliebten Podcasts von [directories] für [category] (eine laufende Abfrage wird abgebrochen). Ein Ergebnis mit
     * ausgefallenem Verzeichnis wird nicht gemerkt, damit ein erneuter Tipp es noch einmal versucht.
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

    /** Ergebnis eines OPML-Imports, einmalig anzuzeigen; `null` = Datei nicht lesbar. */
    val opmlResults: Flow<OpmlImportResult?> = _opmlResults.receiveAsFlow()

    /** Im App-Scope wie in den Einstellungen: Zurück während des Imports bräche sonst mitten im Löschen bzw. Anlegen ab (Review V27). */
    fun importOpml(uri: Uri, replace: Boolean) {
        viewModelScope.launch { _opmlResults.send(applicationScope.async { opmlImporter.import(uri, replace) }.await()) }
    }
}

/** Laden des Feeds für die Vorschau. */
sealed interface PreviewState {
    data object Loading : PreviewState

    /** Als Probeabo angelegt (oder schon vorhanden): weiter auf seiner Seite. */
    data class Opened(val feedId: String) : PreviewState

    /** Schon abonniert, aber archiviert: Erst nach Rückfrage reaktivieren. */
    data class Archived(val feedId: String, val title: String) : PreviewState
    data class Failed(@param:StringRes val message: Int) : PreviewState
}

/** Vorschau eines Treffers (Konzept 12.10): legt den Feed als Probeabo an, damit seine Folgen hör- und ladbar sind. */
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

    /** Das geöffnete Probeabo; es gilt als offen, bis die Seite des Podcasts übernommen hat (sonst räumte es der Aufräumer weg). */
    private var openedFeedId: String? = null

    init {
        load()
    }

    fun load() {
        _state.value = PreviewState.Loading
        viewModelScope.launch {
            // Im App-Scope: Ein großer Feed braucht einige Sekunden; wer vorher zurückgeht, bricht nur das Warten ab.
            val result = applicationScope.async { podcasts.openPreview(feedUrl, imageUrl) }.await()
            _state.value = when (result) {
                is SubscribeResult.Added -> opened(result.feedId)
                is SubscribeResult.AlreadySubscribed -> opened(result.feedId)
                is SubscribeResult.Archived -> PreviewState.Archived(result.feedId, result.title)
                else -> PreviewState.Failed(result.message)
            }
        }
    }

    /** Reaktiviert das archivierte Abo und übergibt dann an seine Seite. */
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
