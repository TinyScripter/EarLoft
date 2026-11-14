package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.ArdSubscription
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBook
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdBookSummary
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdCategory
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ArdShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Was eine Abfrage bei ARD Sounds liefert: lädt, fertig oder gescheitert (ohne Verbindung, Schnittstelle geändert). */
sealed interface ArdLoad<out T> {
    data object Loading : ArdLoad<Nothing>
    data class Done<T>(val value: T) : ArdLoad<T>
    data object Failed : ArdLoad<Nothing>
}

private suspend fun <T> ardLoad(block: suspend () -> T): ArdLoad<T> = try {
    ArdLoad.Done(block())
} catch (e: CancellationException) {
    throw e
} catch (e: IOException) {
    ArdLoad.Failed
}

/**
 * Seite „ARD Sounds“ in *Bücher* (`books_improvements.md` M9): die Sendungen der Rubriken Hörbuch und Hörspiel und die Suche nach
 * Titeln. Gesucht wird erst auf die Lupe, wie bei den Podcasts.
 */
@HiltViewModel
class ArdSoundsViewModel @Inject constructor(private val ardBooks: ArdBooks) : ViewModel() {

    /** Kennung bei ARD Sounds → `bookId` der Bücher in der Bibliothek (Häkchen in den Listen). */
    val library: StateFlow<Map<String, Long>> = ardBooks.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Die abonnierten Sendungen („Meine Reihen“). */
    val subscriptions: StateFlow<List<ArdSubscription>> = ardBooks.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Kennungen der abonnierten Sendungen (Glocke in der Liste). */
    val subscribed: StateFlow<Set<String>> = ardBooks.subscriptions
        .map { list -> list.mapTo(HashSet()) { it.showId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun unsubscribe(showId: String) {
        viewModelScope.launch { ardBooks.unsubscribe(showId) }
    }

    private val _category = MutableStateFlow(ArdCategory.AUDIOBOOK)
    val category: StateFlow<ArdCategory> = _category.asStateFlow()

    private val _shows = MutableStateFlow<ArdLoad<List<ArdShow>>>(ArdLoad.Loading)
    val shows: StateFlow<ArdLoad<List<ArdShow>>> = _shows.asStateFlow()

    /** `null` = keine Suche (die Rubriken stehen da). */
    private val _search = MutableStateFlow<Pair<String, ArdLoad<List<ArdBookSummary>>>?>(null)
    val search: StateFlow<Pair<String, ArdLoad<List<ArdBookSummary>>>?> = _search.asStateFlow()

    /** Je Rubrik gemerkt, solange die Seite lebt (gescheiterte nicht). */
    private val loaded = HashMap<ArdCategory, List<ArdShow>>()
    private var showsJob: Job? = null
    private var searchJob: Job? = null

    init {
        selectCategory(ArdCategory.AUDIOBOOK)
    }

    fun selectCategory(category: ArdCategory) {
        _category.value = category
        showsJob?.cancel()
        loaded[category]?.let {
            _shows.value = ArdLoad.Done(it)
            return
        }
        _shows.value = ArdLoad.Loading
        showsJob = viewModelScope.launch {
            val result = ardLoad { ardBooks.shows(category) }
            if (result is ArdLoad.Done) loaded[category] = result.value
            _shows.value = result
        }
    }

    fun search(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return clearSearch()
        searchJob?.cancel()
        _search.value = trimmed to ArdLoad.Loading
        searchJob = viewModelScope.launch { _search.value = trimmed to ardLoad { ardBooks.search(trimmed) } }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _search.value = null
    }

    fun retry() = selectCategory(_category.value)
}

/** Die Bücher einer Sendung aus ARD Sounds. */
@HiltViewModel(assistedFactory = ArdShowViewModel.Factory::class)
class ArdShowViewModel @AssistedInject constructor(
    @Assisted private val showId: String,
    private val ardBooks: ArdBooks,
) : ViewModel() {

    val library: StateFlow<Map<String, Long>> = ardBooks.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _books = MutableStateFlow<ArdLoad<List<ArdBookSummary>>>(ArdLoad.Loading)
    val books: StateFlow<ArdLoad<List<ArdBookSummary>>> = _books.asStateFlow()

    /** Ob die Sendung abonniert ist: Ihre neuen Bücher kommen dann von selbst in die Bibliothek. */
    val subscribed: StateFlow<Boolean> = ardBooks.subscriptions
        .map { list -> list.any { it.showId == showId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Kanal: Ein Ergebnis während einer Drehung (kein Sammler) bliebe sonst verloren.
    private val _subscription = Channel<SubscriptionResult>(Channel.BUFFERED)

    /** Was ein Tipp auf „Abonnieren“ bewirkt hat, für den Hinweis unten. */
    val subscription: Flow<SubscriptionResult> = _subscription.receiveAsFlow()

    enum class SubscriptionResult { SUBSCRIBED, UNSUBSCRIBED, FAILED }

    init {
        load()
    }

    fun toggleSubscription(title: String) {
        viewModelScope.launch {
            if (subscribed.value) {
                ardBooks.unsubscribe(showId)
                _subscription.send(SubscriptionResult.UNSUBSCRIBED)
            } else {
                val result = try {
                    ardBooks.subscribe(showId, title)
                    SubscriptionResult.SUBSCRIBED
                } catch (e: IOException) {
                    SubscriptionResult.FAILED
                }
                _subscription.send(result)
            }
        }
    }

    fun load() {
        _books.value = ArdLoad.Loading
        viewModelScope.launch { _books.value = ardLoad { ardBooks.showBooks(showId) } }
    }

    @AssistedFactory
    interface Factory {
        fun create(showId: String): ArdShowViewModel
    }
}

/** Was nach dem Aufnehmen in die Bibliothek geschehen soll. */
enum class ArdBookAction { ADD, PLAY, DOWNLOAD }

/**
 * Vorschau eines Buchs aus ARD Sounds: Angaben und Teile von dort. Abspielen, Laden und Hinzufügen nehmen es in die Bibliothek
 * auf (`books_improvements.md` M9: sofort, ohne Probe wie bei den Podcasts); danach übernimmt die normale Buchseite.
 */
@HiltViewModel(assistedFactory = ArdBookPreviewViewModel.Factory::class)
class ArdBookPreviewViewModel @AssistedInject constructor(
    @Assisted private val key: String,
    private val ardBooks: ArdBooks,
    private val launcher: BookLauncher,
    private val downloads: BookDownloads,
) : ViewModel() {

    val library: StateFlow<Map<String, Long>> = ardBooks.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _book = MutableStateFlow<ArdLoad<ArdBook?>>(ArdLoad.Loading)

    /** `Done(null)`: ARD Sounds führt das Buch nicht (mehr). */
    val book: StateFlow<ArdLoad<ArdBook?>> = _book.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    // Kanal: Ein Ergebnis während einer Drehung (kein Sammler) bliebe sonst verloren.
    private val _added = Channel<Added>(Channel.BUFFERED)

    /** Das Buch ist in der Bibliothek; mit [Added.openPlayer] läuft es schon. [Added.message] ist ein Hinweis zum Download. */
    val added: Flow<Added> = _added.receiveAsFlow()

    data class Added(val bookId: Long, val openPlayer: Boolean, val message: Int? = null)

    private val _failed = Channel<Unit>(Channel.CONFLATED)

    /** Aufnehmen ging nicht (keine Verbindung). */
    val failed: Flow<Unit> = _failed.receiveAsFlow()

    init {
        load()
    }

    fun load() {
        _book.value = ArdLoad.Loading
        viewModelScope.launch { _book.value = ardLoad { ardBooks.preview(key) } }
    }

    fun perform(action: ArdBookAction) {
        if (_working.value) return
        _working.value = true
        viewModelScope.launch {
            try {
                val bookId = try {
                    ardBooks.add(key, (_book.value as? ArdLoad.Done)?.value)
                } catch (e: IOException) {
                    null
                }
                if (bookId == null) {
                    _failed.send(Unit)
                    return@launch
                }
                when (action) {
                    ArdBookAction.ADD -> _added.send(Added(bookId, openPlayer = false))
                    ArdBookAction.PLAY -> _added.send(Added(bookId, openPlayer = launcher.playAndAwait(bookId)))
                    ArdBookAction.DOWNLOAD -> {
                        val message = when (downloads.download(bookId)) {
                            DownloadStart.OFFLINE -> R.string.podcast_download_offline
                            DownloadStart.WAITING_FOR_WIFI -> R.string.book_download_waiting_wifi
                            DownloadStart.STARTED -> null
                        }
                        _added.send(Added(bookId, openPlayer = false, message = message))
                    }
                }
            } finally {
                _working.value = false
            }
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(key: String): ArdBookPreviewViewModel
    }
}
