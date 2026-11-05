package de.kleinabersmart.tinyamp.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.repository.FavoritesRepository
import de.kleinabersmart.tinyamp.core.data.repository.LibraryRepository
import de.kleinabersmart.tinyamp.core.data.spoken.BookRepository
import de.kleinabersmart.tinyamp.core.data.spoken.SpokenSettings
import androidx.annotation.StringRes
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastRepository
import de.kleinabersmart.tinyamp.core.model.SeriesHit
import de.kleinabersmart.tinyamp.core.model.AuthorHit
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.model.PodcastFeed
import de.kleinabersmart.tinyamp.core.model.BookWithProgress
import de.kleinabersmart.tinyamp.core.model.SearchResults
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import de.kleinabersmart.tinyamp.core.model.Song
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.ui.components.SongActions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SearchUiState(
    val query: String = "",
    val results: SearchResults = SearchResults.EMPTY,
    val feeds: List<PodcastFeed> = emptyList(),
    val episodes: List<BookWithProgress> = emptyList(),
    val series: List<SeriesHit> = emptyList(),
    val authors: List<AuthorHit> = emptyList(),
    val books: List<BookWithProgress> = emptyList(),
    val scope: SearchScope = SearchScope.ALL,
) {
    val isEmpty: Boolean
        get() = results.isEmpty && feeds.isEmpty() && episodes.isEmpty() && series.isEmpty() && authors.isEmpty() && books.isEmpty()
}

/** Worüber gesucht wird; folgt der Einstellung „gemeinsam durchsuchen“ und sonst dem Modus. */
enum class SearchScope(val music: Boolean, val podcasts: Boolean, val books: Boolean, @StringRes val hint: Int) {
    ALL(true, true, true, R.string.search_hint_all),
    MUSIC(true, false, false, R.string.search_hint),
    PODCASTS(false, true, false, R.string.search_hint_podcasts),
    BOOKS(false, false, true, R.string.search_hint_books),
    ;

    companion object {
        fun of(searchAll: Boolean, mode: SpokenMode): SearchScope = when {
            searchAll -> ALL
            mode == SpokenMode.BOOKS -> BOOKS
            mode == SpokenMode.PODCASTS -> PODCASTS
            else -> MUSIC
        }
    }
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val bookRepository: BookRepository,
    private val podcastRepository: PodcastRepository,
    spokenSettings: SpokenSettings,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel(), SongActions {

    val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchUiState> = combine(
        query.map { it.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else DEBOUNCE_MS },
        combine(spokenSettings.searchAll, spokenSettings.mode, SearchScope::of).distinctUntilChanged(),
        ::Pair,
    ).mapLatest { (query, scope) ->
        if (query.isEmpty()) {
            SearchUiState(scope = scope)
        } else {
            SearchUiState(
                query = query,
                results = if (scope.music) libraryRepository.search(query) else SearchResults.EMPTY,
                feeds = if (scope.podcasts) podcastRepository.searchFeeds(query) else emptyList(),
                episodes = if (scope.podcasts) bookRepository.search(query, BookKind.EPISODE) else emptyList(),
                series = if (scope.books) bookRepository.searchSeries(query) else emptyList(),
                authors = if (scope.books) bookRepository.searchAuthors(query) else emptyList(),
                books = if (scope.books) bookRepository.search(query, BookKind.BOOK) else emptyList(),
                scope = scope,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SearchUiState())

    fun onQueryChange(query: String) {
        savedStateHandle[KEY_QUERY] = query
    }

    fun playSongs(startIndex: Int) {
        playbackConnection.play(results.value.results.songs, startIndex)
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    private companion object {
        const val KEY_QUERY = "query"
        const val DEBOUNCE_MS = 250L
    }
}
