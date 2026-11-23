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

/** What a query to ARD Sounds yields: loading, done or failed (no connection, interface changed). */
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
 * "ARD Sounds" page in *Books* (`books_improvements.md` M9): the shows of the categories audiobook and radio play and the
 * title search. The search only runs on the magnifier, as with podcasts.
 */
@HiltViewModel
class ArdSoundsViewModel @Inject constructor(private val ardBooks: ArdBooks) : ViewModel() {

    /** ID at ARD Sounds → `bookId` of the books in the library (check marks in the lists). */
    val library: StateFlow<Map<String, Long>> = ardBooks.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** The subscribed shows ("My series"). */
    val subscriptions: StateFlow<List<ArdSubscription>> = ardBooks.subscriptions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** IDs of the subscribed shows (bell in the list). */
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

    /** `null` = no search (the categories are shown). */
    private val _search = MutableStateFlow<Pair<String, ArdLoad<List<ArdBookSummary>>>?>(null)
    val search: StateFlow<Pair<String, ArdLoad<List<ArdBookSummary>>>?> = _search.asStateFlow()

    /** Remembered per category while the page lives (failed ones not). */
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

/** The books of a show from ARD Sounds. */
@HiltViewModel(assistedFactory = ArdShowViewModel.Factory::class)
class ArdShowViewModel @AssistedInject constructor(
    @Assisted private val showId: String,
    private val ardBooks: ArdBooks,
) : ViewModel() {

    val library: StateFlow<Map<String, Long>> = ardBooks.library
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _books = MutableStateFlow<ArdLoad<List<ArdBookSummary>>>(ArdLoad.Loading)
    val books: StateFlow<ArdLoad<List<ArdBookSummary>>> = _books.asStateFlow()

    /** Whether the show is subscribed: its new books then arrive in the library automatically. */
    val subscribed: StateFlow<Boolean> = ardBooks.subscriptions
        .map { list -> list.any { it.showId == showId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Channel: a result during a rotation (no collector) would otherwise be lost.
    private val _subscription = Channel<SubscriptionResult>(Channel.BUFFERED)

    /** What a tap on "Subscribe" did, for the hint at the bottom. */
    val subscription: Flow<SubscriptionResult> = _subscription.receiveAsFlow()

    enum class SubscriptionResult { SUBSCRIBED, UNSUBSCRIBED, FAILED }

    init {
        load()
    }

    /** A tap is being handled; a second one meanwhile would subscribe twice (review2 J54). */
    private var toggling = false

    fun toggleSubscription(title: String) {
        if (toggling) return
        toggling = true
        viewModelScope.launch {
            try {
                toggleSubscriptionNow(title)
            } finally {
                toggling = false
            }
        }
    }

    private suspend fun toggleSubscriptionNow(title: String) {
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

    fun load() {
        _books.value = ArdLoad.Loading
        viewModelScope.launch { _books.value = ardLoad { ardBooks.showBooks(showId) } }
    }

    @AssistedFactory
    interface Factory {
        fun create(showId: String): ArdShowViewModel
    }
}

/** What should happen after adding to the library. */
enum class ArdBookAction { ADD, PLAY, DOWNLOAD }

/**
 * Preview of a book from ARD Sounds: details and parts from there. Play, download and add take it into the library
 * (`books_improvements.md` M9: immediately, without a trial as with podcasts); afterwards the regular book page takes over.
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

    /** `Done(null)`: ARD Sounds doesn't carry the book (any more). */
    val book: StateFlow<ArdLoad<ArdBook?>> = _book.asStateFlow()

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working.asStateFlow()

    // Channel: a result during a rotation (no collector) would otherwise be lost.
    private val _added = Channel<Added>(Channel.BUFFERED)

    /** The book is in the library; with [Added.openPlayer] it is already playing. [Added.message] is a hint about the download. */
    val added: Flow<Added> = _added.receiveAsFlow()

    data class Added(val bookId: Long, val openPlayer: Boolean, val message: Int? = null)

    private val _failed = Channel<Unit>(Channel.CONFLATED)

    /** Adding failed (no connection). */
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
