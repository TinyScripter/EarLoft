package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The book queue is the "Continue listening" row without a limit: started, unfinished books. They share the
 * order with the podcasts ([EpisodeQueue]); removing works like "Remove from Continue listening"
 * (state stays, with Audiobookshelf on all devices) until the book plays again.
 */
@Singleton
class BookQueue @Inject constructor(
    books: BookRepository,
    private val queue: EpisodeQueue,
    private val progressSync: BookProgressSync,
) {
    val books: Flow<List<BookWithProgress>> = books.continueListening(limit = Int.MAX_VALUE, startOrder = queue.order)

    /** The queue was reordered: [bookIds] is the new order of the whole row, [movedId] the moved book. */
    suspend fun reorder(bookIds: List<Long>, movedId: Long) = queue.move(bookIds, movedId)

    suspend fun remove(bookId: Long) = progressSync.hideFromContinueListening(bookId)

    /** "Clear queue": all books of the row except [keepId] (the loaded one) out, as if removed individually. */
    suspend fun clear(keepId: Long?) {
        progressSync.hideFromContinueListening(books.first().map { it.book.id }.filter { it != keepId })
    }
}
