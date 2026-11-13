package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
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
    podcasts: PodcastRepository,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    /** Tempo je Abo (sonst die Vorgabe der Podcasts) für die Restzeit von Folgen ohne eigenes Tempo. */
    val feedSpeeds: StateFlow<Map<String, Float>> = combine(podcasts.feeds, spokenSettings.podcastEffects) { feeds, area ->
        feeds.associate { it.id to (it.effects.speed ?: area.speed) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = continueListening.episodes
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Die gerade geladene Folge. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anzahl der Folgen und ihre Restzeit zusammen, jede bei ihrem Tempo (Kopf der Warteschlange); `null` = leer. */
    val summary: StateFlow<QueueSummary?> = combine(continueListening.episodes, feedSpeeds) { episodes, speeds ->
        if (episodes.isEmpty()) {
            null
        } else {
            QueueSummary(episodes.size, episodes.sumOf { it.remainingPlayMs(speeds[it.book.serverId] ?: 1f) ?: 0L })
        }
    }.orOnError(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** „Warteschlange leeren“; die geladene Folge bleibt. */
    fun clear() {
        viewModelScope.launch { continueListening.clear(keepId = currentBookId.value) }
    }

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

data class QueueSummary(val count: Int, val remainingMs: Long)

/**
 * Restzeit der Folge in Wiedergabezeit: beim eigenen Tempo, sonst bei [inheritedSpeed] (Abo bzw. Vorgabe); `null` ohne
 * bekannte Dauer.
 */
internal fun BookWithProgress.remainingPlayMs(inheritedSpeed: Float): Long? {
    val durationMs = progress?.durationMs?.takeIf { it > 0 } ?: book.durationMs
    if (durationMs <= 0) return null
    val positionMs = progress?.positionMs?.coerceIn(0, durationMs) ?: 0
    val speed = (progress?.speed ?: inheritedSpeed).takeIf { it > 0f } ?: 1f
    return ((durationMs - positionMs) / speed).toLong()
}
