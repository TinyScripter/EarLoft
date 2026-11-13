package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class BookAutomationTest {

    private val now = 100L * 24 * 60 * 60 * 1000

    private fun book(id: Long, series: String = "", serverId: String = "abs", progress: BookProgress? = null) = BookWithProgress(
        Book(id = id, serverId = serverId, kind = BookKind.BOOK, title = "B$id", author = "", series = series, durationMs = 1000, remoteId = "r$id"),
        progress,
    )

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

        // Hole: 79 % reicht nicht. Wisting: Band 2 ist schon gehört, also Band 3 – und nach dem beendeten Band 2 ebenso.
        assertEquals(listOf(5L), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = true, now = now))
    }

    @Test
    fun `a volume finished long ago no longer pulls the next one`() {
        val all = listOf(book(1, "Hole #1", progress = at(1000, finished = true, updatedAt = 0)), book(2, "Hole #2"))

        assertEquals(emptyList<Long>(), automaticBookDownloads(emptyList(), all, started = false, nextInSeries = true, now = now))
    }
}
