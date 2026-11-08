package de.kleinabersmart.earloft.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import androidx.annotation.StringRes
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.model.SeriesHit
import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.SearchResults
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.components.SongActions
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

    /** Sucht in [area] – über diesen Umfang und nur, wenn der Bereich eingeschaltet ist ([areas]). */
    fun includes(area: SpokenMode, areas: List<SpokenMode>): Boolean = area in areas && when (area) {
        SpokenMode.MUSIC -> music
        SpokenMode.PODCASTS -> podcasts
        SpokenMode.BOOKS -> books
    }

    companion object {
        /** „Überall suchen“ gilt nur, wenn mehr als ein Bereich eingeschaltet ist ([areas]). */
        fun of(searchAll: Boolean, mode: SpokenMode, areas: List<SpokenMode> = SpokenMode.entries): SearchScope = when {
            searchAll && areas.size > 1 -> ALL
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
        combine(spokenSettings.searchAll, spokenSettings.mode, spokenSettings.areas) { searchAll, mode, areas ->
            SearchScope.of(searchAll, mode, areas) to areas
        }.distinctUntilChanged(),
        ::Pair,
    ).mapLatest { (query, scopeAndAreas) ->
        val (scope, areas) = scopeAndAreas
        if (query.isEmpty()) {
            SearchUiState(scope = scope)
        } else {
            // Ausgeblendete Bereiche durchsucht auch „Überall suchen“ nicht.
            val music = scope.includes(SpokenMode.MUSIC, areas)
            val podcasts = scope.includes(SpokenMode.PODCASTS, areas)
            val books = scope.includes(SpokenMode.BOOKS, areas)
            SearchUiState(
                query = query,
                results = if (music) libraryRepository.search(query) else SearchResults.EMPTY,
                feeds = if (podcasts) podcastRepository.searchFeeds(query) else emptyList(),
                episodes = if (podcasts) bookRepository.search(query, BookKind.EPISODE) else emptyList(),
                series = if (books) bookRepository.searchSeries(query) else emptyList(),
                authors = if (books) bookRepository.searchAuthors(query) else emptyList(),
                books = if (books) bookRepository.search(query, BookKind.BOOK) else emptyList(),
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
