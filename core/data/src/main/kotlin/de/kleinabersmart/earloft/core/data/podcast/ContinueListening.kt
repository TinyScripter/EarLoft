package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.model.BookWithProgress
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Die Zeile „Weiterhören“ und damit die Warteschlange der Podcasts (Konzept 12.4):
 * - Wird eine Folge gestartet, steht sie sofort ganz vorn; ist der Podcast so eingestellt, folgt direkt dahinter seine
 *   nächste ungehörte Folge.
 * - Ist eine Folge zu Ende, startet die nächste der Zeile, wenn das in den Einstellungen an ist.
 */
@Singleton
class ContinueListening @Inject constructor(
    private val queue: EpisodeQueue,
    private val podcasts: PodcastRepository,
    private val settings: PodcastSettings,
) {
    val episodes: Flow<List<BookWithProgress>> = podcasts.continueListening(queue.order, queue.hidden)

    /** Eine Folge wird gestartet (egal ob sie schon in der Zeile steht). Hörbücher bleiben unberührt. */
    suspend fun started(bookId: Long) {
        if (!podcasts.isEpisode(bookId)) return
        // Eine zu Ende gehörte Folge beginnt von vorn und gilt wieder als offen.
        podcasts.reopenIfFinished(bookId)
        queue.moveToFront(bookId)
        podcasts.autoNextAfter(bookId)?.let { queue.insertSecond(it) }
    }

    /** Die Folge, die nach [endedId] von selbst startet: die nächste der Zeile; `null`, wenn das aus ist oder nichts folgt. */
    suspend fun nextAfter(endedId: Long): Long? {
        if (!settings.autoPlayNext.first()) return null
        return next(endedId)
    }

    /** Die nächste Folge der Zeile nach [currentId], unabhängig vom automatischen Weiterspielen (für „Überspringen“). */
    suspend fun next(currentId: Long): Long? = episodes.first().firstOrNull { it.book.id != currentId }?.book?.id

    /**
     * „Als Nächstes spielen“: direkt hinter die vorderste Folge der Zeile (die laufende bzw. die, mit der es weitergeht); ist
     * die Zeile leer, an die erste Stelle.
     */
    suspend fun playNext(bookId: Long) {
        podcasts.reopenIfFinished(bookId)
        val anchor = episodes.first().firstOrNull { it.book.id != bookId }?.book?.id
        queue.insertAfter(bookId, anchor)
    }

    /** „Warteschlange leeren“: alle Folgen der Zeile außer [keepId] (der geladenen) heraus, wie einzeln entfernt. */
    suspend fun clear(keepId: Long?) {
        queue.removeAll(episodes.first().map { it.book.id }.filter { it != keepId })
    }

    /** „In Warteschlange einfügen“: ganz ans Ende der Zeile. */
    suspend fun append(bookId: Long) {
        podcasts.reopenIfFinished(bookId)
        queue.append(bookId)
    }

    /** Die Warteschlange wurde umsortiert: [bookIds] ist die neue Reihenfolge der ganzen Zeile, [movedId] die bewegte Folge. */
    suspend fun reorder(bookIds: List<Long>, movedId: Long) = queue.move(bookIds, movedId)

    /** „Aus Warteschlange entfernen“. */
    suspend fun remove(bookId: Long) = queue.remove(bookId)
}
