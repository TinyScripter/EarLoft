package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.ArdBooks
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.data.spoken.SpokenRefresher
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.spoken.continueSeries
import de.kleinabersmart.earloft.core.data.spoken.genreGroups
import de.kleinabersmart.earloft.core.data.spoken.sortedAndFiltered
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
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

    /** Das Buch, das gerade läuft (nicht: pausiert). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Läuft das Buch, pausiert es; ist es geladen und pausiert, geht es weiter; sonst startet oder setzt es fort
     * (und steht danach ganz vorn in „Weiterhören“).
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

    /** Start-Regal „Serie fortsetzen“: je Serie der nächste Band mit seiner Nummer ([continueSeries]). */
    val continueSeries: StateFlow<List<Pair<BookWithProgress, String?>>> = books.books()
        .map { all -> all.continueSeries().map { (book, series) -> book to series.nextVolume } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val allBooks: StateFlow<LoadState<List<BookWithProgress>>> = books.books()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val bookSort: StateFlow<BookSort> = settings.bookSort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookSort.TITLE)

    val bookFilter: StateFlow<BookFilter> = settings.bookFilter
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookFilter.ALL)

    /** Alle Genres der Bücher nach Name (Filter des Reiters „Alle“); leer, wenn der Server keine führt. */
    val genres: StateFlow<List<String>> = books.books().map { all -> all.genreGroups().map { it.name } }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gewähltes Genre; eines, das kein Buch mehr hat, gilt als keins. */
    val bookGenre: StateFlow<String?> = combine(settings.bookGenre, genres) { genre, all -> genre?.takeIf { it in all } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Die gezeigten Buch-Bibliotheken eingeschalteter Server als (Kennung, Name), für den Filter des Reiters „Alle“ (M3); den
     * Filter gibt es erst ab zwei. Gleiche Namen auf mehreren Servern tragen den Servernamen dahinter.
     */
    val libraries: StateFlow<List<Pair<String, String>>> = servers.servers.map { list ->
        val shown = list.filter { it.enabled }.flatMap { server -> server.libraries.filter { it.shown }.map { server to it } }
        val repeated = shown.groupingBy { it.second.name }.eachCount().filterValues { it > 1 }.keys
        shown.map { (server, library) -> library.libraryId to if (library.name in repeated) "${library.name} (${server.name})" else library.name }
    }
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Gewählte Bibliothek; eine, die es nicht mehr gibt (abgewählt, Server aus), gilt als keine. */
    val bookLibrary: StateFlow<String?> = combine(settings.bookLibrary, libraries) { id, all -> id?.takeIf { all.size > 1 && all.any { it.first == id } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reiter „Alle“: nach Filter, Genre, Bibliothek und Reihenfolge; `null`, solange noch geladen wird. */
    val shownBooks: StateFlow<List<BookWithProgress>?> = combine(
        books.books(),
        combine(settings.bookSort, settings.bookFilter, ::Pair),
        books.downloads().map { map -> map.filterValues { it.state == TrackDownload.DONE }.keys },
        settings.bookGenre,
        bookLibrary,
    ) { all, (sort, filter), downloaded, genre, library ->
        all.sortedAndFiltered(sort, filter, downloaded, genre?.takeIf { g -> all.any { g in it.book.genres } }, library)
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

    /** Hörbücher und Hörspiele aus ARD Sounds sind eingeschaltet (`books_improvements.md` M9): Start zeigt den Weg dorthin. */
    val ardSounds: StateFlow<Boolean> = settings.ardSoundsBooks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Ein Audiobookshelf-Server oder ARD Sounds als Quelle (bzw. Bücher von dort); `null`, solange noch geladen wird. */
    val hasServer: StateFlow<Boolean?> = combine(servers.servers, settings.ardSoundsBooks, ardBooks.library) { list, ard, ardLibrary ->
        list.isNotEmpty() || ard || ardLibrary.isNotEmpty()
    }
        .map<_, Boolean?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Ein eingeschalteter Server ohne gültige Anmeldung bzw. ohne API-Key (abgelaufen, nach einer Wiederherstellung): Ist
     * die Bibliothek leer, sagt sie das statt „noch keine Bücher“.
     */
    val signInNeeded: StateFlow<SpokenServer?> = combine(servers.servers, servers.serversWithoutKey) { list, missing ->
        list.firstOrNull { it.enabled && it.serverId in missing }
    }
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun syncNow() = refresher.refreshAll()

    /** Beschreibung des Autors von Audiobookshelf für die Autorenseite (`books_improvements.md` M8). */
    fun authorDescription(name: String): Flow<String?> = books.authorDescription(name)
}
