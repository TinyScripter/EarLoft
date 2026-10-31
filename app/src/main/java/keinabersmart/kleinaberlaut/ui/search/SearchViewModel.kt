package keinabersmart.kleinaberlaut.ui.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenSettings
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.SearchResults
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.components.SongActions
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
    val books: List<BookWithProgress> = emptyList(),
    val scope: SearchScope = SearchScope.ALL,
) {
    val isEmpty: Boolean get() = results.isEmpty && books.isEmpty()
}

/** Worüber gesucht wird; folgt der Einstellung „gemeinsam durchsuchen“ und sonst dem Modus. */
enum class SearchScope(val music: Boolean, val books: Boolean) {
    ALL(true, true),
    MUSIC(true, false),
    SPOKEN(false, true),
}

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val libraryRepository: LibraryRepository,
    private val bookRepository: BookRepository,
    spokenSettings: SpokenSettings,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel(), SongActions {

    val query: StateFlow<String> = savedStateHandle.getStateFlow(KEY_QUERY, "")

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<SearchUiState> = combine(
        query.map { it.trim() }.distinctUntilChanged().debounce { if (it.isEmpty()) 0 else DEBOUNCE_MS },
        combine(spokenSettings.searchAll, spokenSettings.mode) { all, mode ->
            when {
                all -> SearchScope.ALL
                mode == SpokenMode.SPOKEN -> SearchScope.SPOKEN
                else -> SearchScope.MUSIC
            }
        }.distinctUntilChanged(),
        ::Pair,
    ).mapLatest { (query, scope) ->
        if (query.isEmpty()) {
            SearchUiState(scope = scope)
        } else {
            SearchUiState(
                query = query,
                results = if (scope.music) libraryRepository.search(query) else SearchResults.EMPTY,
                books = if (scope.books) bookRepository.search(query) else emptyList(),
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
