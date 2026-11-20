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
    private val podcasts: PodcastRepository,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    /** Speed per subscription (otherwise the podcasts default) for the remaining time of episodes without their own speed. */
    val feedSpeeds: StateFlow<Map<String, Float>> =
        combine(podcasts.feeds, spokenSettings.podcastEffects, continueListening.episodes) { feeds, area, episodes ->
            val own = feeds.associate { it.id to (it.effects.speed ?: area.speed) }
            // Episodes of trial subscriptions (not in [podcasts.feeds]) at the podcasts default, not at 1× (review G55).
            own + episodes.map { it.book.serverId }.filter { it !in own }.associateWith { area.speed }
        }.orOnError(emptyMap()).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = continueListening.episodes
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** The currently loaded episode. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Number of episodes and their combined remaining time, each at its own speed (queue header); `null` = empty. */
    val summary: StateFlow<QueueSummary?> = combine(continueListening.episodes, feedSpeeds) { episodes, speeds ->
        if (episodes.isEmpty()) {
            null
        } else {
            QueueSummary(episodes.size, episodes.sumOf { it.remainingPlayMs(speeds[it.book.serverId] ?: 1f) ?: 0L })
        }
    }.orOnError(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Clear queue"; the loaded episode stays. */
    fun clear() {
        viewModelScope.launch { continueListening.clear(keepId = currentBookId.value) }
    }

    private val searchQuery = MutableStateFlow("")

    /** Search text of the [PodcastQueueSearchScreen] page; the results come via [searchHits]. */
    fun search(text: String) {
        searchQuery.value = text.trim()
    }

    /** Show notes beginnings of the queued episodes; re-read only when the queue's episodes change, not on every progress save. */
    private var notes: Pair<List<Long>, Map<Long, String>> = emptyList<Long>() to emptyMap()

    private suspend fun notesOf(ids: List<Long>): Map<Long, String> {
        if (notes.first != ids) notes = ids to podcasts.descriptionStarts(ids)
        return notes.second
    }

    /**
     * Matches in title, summary and podcast name, in queue order. The queue comes without descriptions; their beginnings are
     * added here (review G22).
     */
    @OptIn(FlowPreview::class)
    val searchHits: StateFlow<EpisodeHits?> = combine(
        continueListening.episodes.map { episodes ->
            val starts = notesOf(episodes.map { it.book.id })
            episodes.map { episode ->
                val described = starts[episode.book.id]?.let { episode.copy(book = episode.book.copy(description = it)) } ?: episode
                SearchEntry(described, withPodcast = true)
            }
        },
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

    fun reorder(bookIds: List<Long>, movedId: Long) {
        viewModelScope.launch { continueListening.reorder(bookIds, movedId) }
    }
}

data class QueueSummary(val count: Int, val remainingMs: Long)

/**
 * Remaining time of the episode in playback time: at its own speed, otherwise at [inheritedSpeed] (subscription or default); `null` without
 * a known duration.
 */
internal fun BookWithProgress.remainingPlayMs(inheritedSpeed: Float): Long? {
    val durationMs = progress?.durationMs?.takeIf { it > 0 } ?: book.durationMs
    if (durationMs <= 0) return null
    val positionMs = progress?.positionMs?.coerceIn(0, durationMs) ?: 0
    val speed = (progress?.speed ?: inheritedSpeed).takeIf { it > 0f } ?: 1f
    return ((durationMs - positionMs) / speed).toLong()
}
