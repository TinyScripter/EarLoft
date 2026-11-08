package de.kleinabersmart.tinyamp.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.common.normalizeForMerge
import de.kleinabersmart.tinyamp.core.data.podcast.ContinueListening
import de.kleinabersmart.tinyamp.core.model.BookWithProgress
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.asLoadState
import de.kleinabersmart.tinyamp.ui.orOnError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastQueueViewModel @Inject constructor(
    private val continueListening: ContinueListening,
    playback: PlaybackConnection,
) : ViewModel() {

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = continueListening.episodes
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Die gerade geladene Folge. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val searchQuery = MutableStateFlow("")

    /** Suchtext der Seite [PodcastQueueSearchScreen]; die Treffer kommen über [searchHits]. */
    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    /** Treffer in Überschrift, Inhaltsangabe und Name des Podcasts, in der Reihenfolge der Warteschlange. */
    @OptIn(FlowPreview::class)
    val searchHits: StateFlow<EpisodeHits?> = combine(
        continueListening.episodes.map { episodes -> episodes.map { SearchEntry(it, withPodcast = true) } },
        searchQuery.debounce { if (it.isEmpty()) 0L else SEARCH_DEBOUNCE_MS },
    ) { index, query ->
        val needle = query.normalizeForMerge()
        EpisodeHits(query, if (needle.isEmpty()) emptyList() else index.filter { it.matches(needle) }.map { it.episode })
    }
        .flowOn(Dispatchers.Default)
        .orOnError(null)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun remove(bookId: Long) {
        viewModelScope.launch { continueListening.remove(bookId) }
    }

    fun reorder(bookIds: List<Long>) {
        viewModelScope.launch { continueListening.reorder(bookIds) }
    }
}
