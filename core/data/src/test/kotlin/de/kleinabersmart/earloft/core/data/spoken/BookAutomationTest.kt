package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.ARD_BOOK_SERVER_ID
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class BookAutomationTest {

    private val now = 100L * 24 * 60 * 60 * 1000

    private fun book(id: Long, series: String = "", serverId: String = "abs", progress: BookProgress? = null, availableUntil: Long? = null) = BookWithProgress(
        Book(
            id = id, serverId = serverId, kind = BookKind.BOOK, title = "B$id", author = "", series = series, durationMs = 1000, remoteId = "r$id",
            availableUntil = availableUntil,
        ),
        progress,
    )

    private fun days(count: Int) = count * 24 * 60 * 60 * 1000L

    private fun at(positionMs: Long, finished: Boolean = false, updatedAt: Long = now) =
        BookProgress(positionMs = positionMs, durationMs = 1000, finished = finished, updatedAt = updatedAt)

    @Test
    fun `started books are the first two of continue listening without books from the device`() {
        val list = listOf(book(1, serverId = "local:x"), book(2), book(3), book(4))

        assertEquals(listOf(2L, 3L), automaticBookDownloads(list, list, started = true, nextInSeries = false, now = now))
        assertEquals(emptyList<Long>(), automaticBookDownloads(list, list, started = false, nextInSeries = false, now = now))
    }

    @Test
    fun `the next volume loads once the current one is at eighty percent`() {
        val all = listOf(
            book(1, "Hole #13", progress = at(790)),
            book(2, "Hole #14"),
            book(3, "Wisting #1", progress = at(800)),
            book(4, "Wisting #2", progress = at(1000, finished = true)),
            book(5, "Wisting #3"),
        )

        // Hole: 79 % isn't enough. Wisting: volume 2 is already played, so volume 3 – and likewise after the finished volume 2.
        assertEquals(listOf(5L), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = true, now = now))
    }

    @Test
    fun `ARD books expiring within a week load, the soonest first`() {
        val all = listOf(
            book(1, serverId = ARD_BOOK_SERVER_ID, availableUntil = now + days(6)),
            book(2, serverId = ARD_BOOK_SERVER_ID, availableUntil = now + days(2)),
            // Still available for a long time, already expired, finished, without deadline and from a server: no.
            book(3, serverId = ARD_BOOK_SERVER_ID, availableUntil = now + days(30)),
            book(4, serverId = ARD_BOOK_SERVER_ID, availableUntil = now - 1),
            book(5, serverId = ARD_BOOK_SERVER_ID, availableUntil = now + days(1), progress = at(1000, finished = true)),
            book(6, serverId = ARD_BOOK_SERVER_ID),
            book(7, availableUntil = now + days(1)),
            // Started counts like not started: it's in the library, so it should stay.
            book(8, serverId = ARD_BOOK_SERVER_ID, availableUntil = now + days(3), progress = at(100)),
        )

        assertEquals(listOf(2L, 8L, 1L), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = false, now = now, expiring = true))
        assertEquals(emptyList<Long>(), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = false, now = now))
    }

    @Test
    fun `a volume finished long ago no longer pulls the next one`() {
        val all = listOf(book(1, "Hole #1", progress = at(1000, finished = true, updatedAt = 0)), book(2, "Hole #2"))

        assertEquals(emptyList<Long>(), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = true, now = now))
    }
}
