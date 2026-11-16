package de.kleinabersmart.earloft.wear

import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteSpokenListsTest {

    private fun entry(id: Long, positionMs: Long = 0, finished: Boolean = false, updatedAt: Long = 0) = BookWithProgress(
        Book(id, "srv", BookKind.BOOK, "Titel $id", "Autor", durationMs = 1_000, remoteId = "r$id"),
        progress = if (positionMs > 0 || finished) BookProgress(positionMs, 1_000, finished, updatedAt = updatedAt) else null,
    )

    @Test
    fun `Buecher - angefangene zuerst, zuletzt gehoertes vorn, Rest wie geliefert`() {
        val books = listOf(entry(1), entry(2, positionMs = 10, updatedAt = 5), entry(3, finished = true), entry(4, positionMs = 10, updatedAt = 9))
        val remote = remoteBooks(books)
        assertEquals(listOf(4L, 2L, 1L, 3L), remote.map { it.id })
        assertEquals(listOf(true, true, false, false), remote.map { it.started })
        assertEquals("r4", remote.first().remoteId)
    }

    @Test
    fun `Folgen - angefangene auch aeltere, dann die neuesten`() {
        // Neueste zuerst geliefert; Folge 9 ist alt, aber angefangen.
        val episodes = listOf(entry(1), entry(2), entry(3, finished = true), entry(4), entry(9, positionMs = 10))
        assertEquals(listOf(9L, 1L, 2L), remoteEpisodes(episodes, newest = 2).map { it.id })
    }

    @Test
    fun `Warteschlange - Reihenfolge bleibt, auch angefangene rutschen nicht nach vorn, höchstens 50`() {
        val queue = listOf(entry(5), entry(3, positionMs = 10, updatedAt = 9), entry(8)) + (100L..160L).map { entry(it) }
        val remote = remoteQueue(queue)
        assertEquals(listOf(5L, 3L, 8L), remote.take(3).map { it.id })
        assertEquals(listOf(false, true, false), remote.take(3).map { it.started })
        assertEquals(50, remote.size)
    }
}
