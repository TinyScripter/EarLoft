package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die Warteschlange der Bücher ist die Zeile „Weiterhören“ ohne Begrenzung: angefangene, nicht beendete Bücher. Die
 * Reihenfolge teilen sie sich mit den Podcasts ([EpisodeQueue]); herausgenommen wird wie mit „Aus Weiterhören entfernen“
 * (Stand bleibt, bei Audiobookshelf auf allen Geräten), bis das Buch wieder läuft.
 */
@Singleton
class BookQueue @Inject constructor(
    books: BookRepository,
    private val queue: EpisodeQueue,
    private val progressSync: BookProgressSync,
) {
    val books: Flow<List<BookWithProgress>> = books.continueListening(limit = Int.MAX_VALUE, startOrder = queue.order)

    /** Die Warteschlange wurde umsortiert: [bookIds] ist die neue Reihenfolge der ganzen Zeile, [movedId] das bewegte Buch. */
    suspend fun reorder(bookIds: List<Long>, movedId: Long) = queue.move(bookIds, movedId)

    suspend fun remove(bookId: Long) = progressSync.hideFromContinueListening(bookId)

    /** „Warteschlange leeren“: alle Bücher der Zeile außer [keepId] (dem geladenen) heraus, wie einzeln entfernt. */
    suspend fun clear(keepId: Long?) {
        progressSync.hideFromContinueListening(books.first().map { it.book.id }.filter { it != keepId })
    }
}
