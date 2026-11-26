package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.orOnError
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.spoken.BookGroup
import de.kleinabersmart.earloft.core.data.spoken.SeriesProgress
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.spoken.authorGroups
import de.kleinabersmart.earloft.core.data.spoken.continueSeries
import de.kleinabersmart.earloft.core.data.spoken.genreGroups
import de.kleinabersmart.earloft.core.data.spoken.hasGenre
import de.kleinabersmart.earloft.core.data.spoken.sameGenre
import de.kleinabersmart.earloft.core.data.spoken.seriesGroups
import de.kleinabersmart.earloft.core.data.spoken.seriesProgress
import de.kleinabersmart.earloft.core.data.spoken.sortedAndFiltered
import de.kleinabersmart.earloft.core.data.spoken.sortedAuthors
import de.kleinabersmart.earloft.core.data.spoken.sortedSeries
import de.kleinabersmart.earloft.core.model.AuthorSort
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.SeriesSort
import de.kleinabersmart.earloft.core.model.BookSort
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SpokenLibraryViewModel @Inject constructor(
    private val books: BookRepository,
    private val servers: AudiobookshelfServers,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    queue: EpisodeQueue,
    private val refresher: SpokenRefresher,
    private val settings: SpokenSettings,
    ardBooks: ArdBooks,
) : ViewModel() {

    /** The book currently playing (not: paused). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * If the book is playing, it pauses; if it is loaded and paused, it continues; otherwise it starts or resumes
     * (and is then at the very front of "Continue listening").
     */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }

    val continueListening: StateFlow<LoadState<List<BookWithProgress>>> = books.continueListening(startOrder = queue.order)
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val recentlyAdded: StateFlow<LoadState<List<BookWithProgress>>> = books.recentlyAdded()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Start shelf "Continue series": per series the next volume with its number ([continueSeries]). */
    val continueSeries: StateFlow<List<Pair<BookWithProgress, String?>>> = books.books()
        .map { all -> all.continueSeries().map { (book, series) -> book to series.nextVolume } }
        // Recomputed on every progress row (every 15 s during playback), not on the main thread (review D12).
        .flowOn(Dispatchers.Default)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Whether the library has any book, for the hint on the start page, which doesn't need the books (review4 Y21). */
    val hasBooks: StateFlow<LoadState<Boolean>> = books.hasBooks()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val allBooks: StateFlow<LoadState<List<BookWithProgress>>> = books.books()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val bookSort: StateFlow<BookSort> = settings.bookSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSort.TITLE)

    val bookFilter: StateFlow<BookFilter> = settings.bookFilter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookFilter.ALL)

    /** All genres of the books by name (filter of the "All" tab); empty if the server has none. */
    val genres: StateFlow<List<String>> = books.books().map { all -> all.genreGroups().map { it.name } }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selected genre; one that no longer has any book counts as none. */
    val bookGenre: StateFlow<String?> = combine(settings.bookGenre, genres) { genre, all -> genre?.let { g -> all.firstOrNull { sameGenre(it, g) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * The shown book libraries of enabled servers as (ID, name), for the filter of the "All" tab (M3); the
     * filter only exists from two upward. Identical names on several servers carry the server name after them.
     */
    val libraries: StateFlow<List<Pair<String, String>>> = servers.servers.map { list ->
        val shown = list.filter { it.enabled }.flatMap { server -> server.libraries.filter { it.shown }.map { server to it } }
        val repeated = shown.groupingBy { it.second.name }.eachCount().filterValues { it > 1 }.keys
        shown.map { (server, library) -> library.libraryId to if (library.name in repeated) "${library.name} (${server.name})" else library.name }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Selected library; one that no longer exists (deselected, server off) counts as none. */
    val bookLibrary: StateFlow<String?> = combine(settings.bookLibrary, libraries) { id, all -> id?.takeIf { all.size > 1 && all.any { it.first == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "All" tab: by filter, genre, library and order; `null` while still loading. */
    val shownBooks: StateFlow<List<BookWithProgress>?> = combine(
        books.books(),
        combine(settings.bookSort, settings.bookFilter, ::Pair),
        books.downloads().map { map -> map.filterValues { it.state == TrackDownload.DONE }.keys },
        settings.bookGenre,
        bookLibrary,
    ) { all, (sort, filter), downloaded, genre, library ->
        all.sortedAndFiltered(sort, filter, downloaded, genre?.takeIf { g -> all.any { it.book.hasGenre(g) } }, library)
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setBookLibrary(libraryId: String?) {
        viewModelScope.launch { settings.setBookLibrary(libraryId) }
    }

    fun setBookGenre(genre: String?) {
        viewModelScope.launch { settings.setBookGenre(genre) }
    }

    val seriesSort: StateFlow<SeriesSort> = settings.seriesSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SeriesSort.NAME)

    val authorSort: StateFlow<AuthorSort> = settings.authorSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AuthorSort.NAME)

    /**
     * The series with their progress, grouped off the main thread: the books change with every saved position, every 15 s
     * during playback (review2 J55).
     */
    val seriesGroups: StateFlow<List<SeriesProgress>> = combine(books.books(), settings.seriesSort) { all, sort ->
        all.seriesGroups().sortedSeries(sort).seriesProgress()
    }
        .flowOn(Dispatchers.Default)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** The authors, regrouped only when a book changes, not its progress (the authors page shows none). */
    val authorGroups: StateFlow<List<BookGroup>> = combine(
        books.books().distinctUntilChangedBy { all -> all.map { it.book } },
        settings.authorSort,
    ) { all, sort -> all.authorGroups().sortedAuthors(sort) }
        .flowOn(Dispatchers.Default)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setSeriesSort(sort: SeriesSort) {
        viewModelScope.launch { settings.setSeriesSort(sort) }
    }

    fun setAuthorSort(sort: AuthorSort) {
        viewModelScope.launch { settings.setAuthorSort(sort) }
    }

    fun setBookSort(sort: BookSort) {
        viewModelScope.launch { settings.setBookSort(sort) }
    }

    fun setBookFilter(filter: BookFilter) {
        viewModelScope.launch { settings.setBookFilter(filter) }
    }

    /** Audiobooks and radio plays from ARD Sounds are enabled (`books_improvements.md` M9): start shows the way there. */
    val ardSounds: StateFlow<Boolean> = settings.ardSoundsBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * An Audiobookshelf server, ARD Sounds or the books on this device as a source (or books from ARD Sounds);
     * `null` while still loading. Books on the device alone are enough: without them counting, the area only
     * offered to set up Audiobookshelf although the device's books were in the library.
     */
    val hasServer: StateFlow<Boolean?> = combine(
        servers.servers,
        settings.ardSoundsBooks,
        settings.useLocalBooks,
        ardBooks.library,
    ) { list, ard, local, ardLibrary ->
        list.isNotEmpty() || ard || local || ardLibrary.isNotEmpty()
    }
        .map<_, Boolean?> { it }
        // Stays "loading" instead of ending the app (review2 J49).
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * An enabled server without a valid login or without an API key (expired, after a restore): if
     * the library is empty, it says so instead of "no books yet".
     */
    val signInNeeded: StateFlow<SpokenServer?> = combine(servers.servers, servers.serversWithoutKey) { list, missing ->
        list.firstOrNull { it.enabled && it.serverId in missing }
    }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = refresher.refreshAll()

    /** Description of the author from Audiobookshelf for the author page (`books_improvements.md` M8). */
    fun authorDescription(name: String): Flow<String?> = books.authorDescription(name)
}
