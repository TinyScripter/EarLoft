package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Recently added" row on the podcast Home (concept 12.5): which episodes it shows ([PodcastRepository.recentEpisodes]) and
 * the ways to take them out. Removing a single episode uses the same list as removing it from "Continue listening"
 * ([EpisodeQueue.hidden]); clearing the whole row only hides what it showed until newer episodes arrive.
 */
@Singleton
class RecentlyAdded @Inject constructor(
    private val queue: EpisodeQueue,
    podcasts: PodcastRepository,
) {
    /** Combines all episodes with all progress (every 15 s during playback), therefore off the main thread. */
    val episodes: Flow<List<BookWithProgress>> =
        podcasts.recentEpisodes(queue.hidden, queue.recentClearedUntil).flowOn(Dispatchers.Default)

    /** Takes the episode out of this row and out of "Continue listening"; it comes back by playing or inserting it. */
    suspend fun hide(bookId: Long) = queue.remove(bookId)

    /** Undoes [hide] for an episode that wasn't in the fixed order of "Continue listening". */
    suspend fun unhide(bookId: Long) = queue.unhide(bookId)

    /** Empties the row until an episode newer than the ones it shows arrives. */
    suspend fun clear() {
        val newest = episodes.first().maxOfOrNull { it.book.addedAt } ?: return
        queue.setRecentClearedUntil(newest)
    }
}
