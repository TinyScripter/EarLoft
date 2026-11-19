package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The "Continue listening" row and thus the podcast queue (concept 12.4):
 * - When an episode is started, it immediately moves to the very front; if the podcast is configured that way, its
 *   next unplayed episode follows right behind, unless it was manually removed from the row.
 * - When an episode ends, the next one in the row starts, if enabled in the settings.
 */
@Singleton
class ContinueListening @Inject constructor(
    private val queue: EpisodeQueue,
    private val podcasts: PodcastRepository,
    private val settings: PodcastSettings,
) {
    val episodes: Flow<List<BookWithProgress>> = podcasts.continueListening(queue.order, queue.hidden)

    /** An episode is started (regardless of whether it is already in the row). Audiobooks are left untouched. */
    suspend fun started(bookId: Long) {
        if (!podcasts.isEpisode(bookId)) return
        // An episode played to the end starts over and counts as open again.
        podcasts.reopenIfFinished(bookId)
        queue.moveToFront(bookId)
        // An episode removed from the row by hand doesn't come back on its own, only by playing or inserting it.
        podcasts.autoNextAfter(bookId)?.takeIf { it !in queue.hidden.first() }?.let { queue.insertSecond(it) }
    }

    /** The episode that starts automatically after [endedId]: the next one in the row; `null` if that is off or nothing follows. */
    suspend fun nextAfter(endedId: Long): Long? {
        if (!settings.autoPlayNext.first()) return null
        return next(endedId)
    }

    /** The next episode in the row after [currentId], independent of auto-play (for "Skip"). */
    suspend fun next(currentId: Long): Long? = episodes.first().firstOrNull { it.book.id != currentId }?.book?.id

    /**
     * "Play next": right behind the frontmost episode of the row (the one playing or the one playback continues with); if
     * the row is empty, in first position.
     */
    suspend fun playNext(bookId: Long) {
        podcasts.reopenIfFinished(bookId)
        val anchor = episodes.first().firstOrNull { it.book.id != bookId }?.book?.id
        queue.insertAfter(bookId, anchor)
    }

    /**
     * Several episodes "next", in the order of [bookIds]; the frontmost episode itself stays where it is. If the
     * row is empty, the first of the selection becomes the frontmost and the others follow it.
     */
    suspend fun playNext(bookIds: List<Long>) {
        val head = episodes.first().firstOrNull()?.book?.id
        val rest = bookIds.filter { it != head }
        if (head == null && rest.isNotEmpty()) playNext(rest.first())
        // From the back, because each one lands right behind the frontmost.
        (if (head == null) rest.drop(1) else rest).asReversed().forEach { playNext(it) }
    }

    /** "Clear queue": remove all episodes of the row except [keepId] (the loaded one), as if removed individually. */
    suspend fun clear(keepId: Long?) {
        queue.removeAll(episodes.first().map { it.book.id }.filter { it != keepId })
    }

    /** "Add to queue": at the very end of the row. */
    suspend fun append(bookId: Long) {
        podcasts.reopenIfFinished(bookId)
        queue.append(bookId)
    }

    /** The queue was reordered: [bookIds] is the new order of the whole row, [movedId] the moved episode. */
    suspend fun reorder(bookIds: List<Long>, movedId: Long) = queue.move(bookIds, movedId)

    /** "Remove from queue". */
    suspend fun remove(bookId: Long) = queue.remove(bookId)
}
