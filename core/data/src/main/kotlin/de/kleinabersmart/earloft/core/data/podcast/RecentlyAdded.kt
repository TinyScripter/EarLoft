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
    podcasts: PodcastRepository,
) {
    /** Combines all episodes with all progress (every 15 s during playback), therefore off the main thread. */
    val episodes: Flow<List<BookWithProgress>> =
        podcasts.recentEpisodes(combine(queue.hidden, queue.recentHidden) { a, b -> a + b }, queue.recentClearedUntil)
            .flowOn(Dispatchers.Default)

    /** Takes the episode out of this row only. */
    suspend fun hide(bookId: Long) = queue.hideFromRecent(bookId)

    /** Undoes [hide]. */
    suspend fun unhide(bookId: Long) = queue.unhideFromRecent(bookId)

    /** Empties the row until an episode newer than the ones it shows arrives. */
    suspend fun clear() {
        val newest = episodes.first().maxOfOrNull { it.book.addedAt } ?: return
        queue.setRecentClearedUntil(newest)
    }
}
