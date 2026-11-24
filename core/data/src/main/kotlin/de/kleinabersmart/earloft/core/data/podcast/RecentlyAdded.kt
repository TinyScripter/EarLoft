package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Recently added" row on the podcast Home (concept 12.5): which episodes it shows ([PodcastRepository.recentEpisodes]) and
 * the ways to take them out. Episodes removed from "Continue listening" ([EpisodeQueue.hidden]) are missing here too; removing
 * an episode or clearing the row here never touches "Continue listening".
 */
@Singleton
class RecentlyAdded @Inject constructor(
    private val queue: EpisodeQueue,
    private val podcasts: PodcastRepository,
) {
    /** Removed by hand here or from "Continue listening". */
    private val removed = combine(queue.hidden, queue.recentHidden) { a, b -> a + b }

    /** Combines all episodes with all progress (every 15 s during playback), therefore off the main thread. */
    val episodes: Flow<List<BookWithProgress>> =
        podcasts.recentEpisodes(combine(removed, queue.recentCleared) { a, b -> a + b })
            .flowOn(Dispatchers.Default)

    /** Takes the episode out of this row only. */
    suspend fun hide(bookId: Long) = queue.hideFromRecent(bookId)

    /** Undoes [hide]. */
    suspend fun unhide(bookId: Long) = queue.unhideFromRecent(bookId)

    /**
     * Empties the row until another episode arrives: all it could show now, not only the first ones it shows, otherwise the
     * next ones would move up at once.
     */
    suspend fun clear() {
        val all = podcasts.recentEpisodes(removed, limit = Int.MAX_VALUE).flowOn(Dispatchers.Default).first()
        if (all.isEmpty()) return
        queue.setRecentCleared(all.map { it.book.id })
    }
}
