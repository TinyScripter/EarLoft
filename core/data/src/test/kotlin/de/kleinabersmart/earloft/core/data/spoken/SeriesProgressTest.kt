package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Series as a concept (`books_improvements.md` M2): progress, next volume, "Continue series", card in the player. */
class SeriesProgressTest {

    private fun book(id: Long, series: String, progress: BookProgress? = null) =
        BookWithProgress(Book(id, "abs", BookKind.BOOK, "Buch $id", "A", series = series, durationMs = 1_000, remoteId = "$id"), progress)

    private fun finished(at: Long) = BookProgress(1_000, 1_000, finished = true, updatedAt = at)
    private fun started(at: Long, position: Long = 300) = BookProgress(position, 1_000, finished = false, updatedAt = at)

    private fun progressOf(vararg books: BookWithProgress) = books.toList().seriesGroups().seriesProgress().single()

    @Test
    fun `nothing heard starts with the first volume`() {
        val series = progressOf(book(2, "Saga #2"), book(1, "Saga #1"), book(3, "Saga #3"))
        assertEquals(1L, series.next?.book?.id)
        assertEquals(SeriesStep.START, series.step)
        assertEquals(0, series.finished)
        assertEquals(3, series.total)
    }

    @Test
    fun `after finished volumes it goes on with the next one after the last finished`() {
        val series = progressOf(
            book(1, "Saga #1", finished(10)),
            book(2, "Saga #2"),
            book(3, "Saga #3", finished(20)),
            book(4, "Saga #4"),
        )
        assertEquals(4L, series.next?.book?.id)
        assertEquals("4", series.nextVolume)
        assertEquals(SeriesStep.NEXT, series.step)
        assertEquals(2, series.finished)
        assertEquals(20L, series.lastFinishedAt)
    }

    @Test
    fun `a gap before is next when everything after is finished, nothing when all are`() {
        val gap = progressOf(book(1, "Saga #1"), book(2, "Saga #2", finished(10)))
        assertEquals(1L, gap.next?.book?.id)
        val done = progressOf(book(1, "Saga #1", finished(5)), book(2, "Saga #2", finished(10)))
        assertNull(done.next)
        assertNull(done.step)
    }

    @Test
    fun `a started volume wins`() {
        val series = progressOf(book(1, "Saga #1", finished(10)), book(2, "Saga #2"), book(3, "Saga #3", started(30)))
        assertEquals(3L, series.next?.book?.id)
        assertEquals(SeriesStep.CONTINUE, series.step)
    }

    @Test
    fun `continue series lists the next volume of series without a started one, latest finished first`() {
        val all = listOf(
            book(1, "Saga #1", finished(10)),
            book(2, "Saga #2"),
            book(11, "Reihe #1", finished(50)),
            book(12, "Reihe #2"),
            // Started: is in "Continue listening", not here.
            book(21, "Krimis #1", finished(60)),
            book(22, "Krimis #2", started(70)),
            // Nothing played.
            book(31, "Neu #1"),
        )
        assertEquals(listOf(12L, 2L), all.continueSeries().map { it.first.book.id })
    }

    @Test
    fun `the next volume after a finished book has a higher number and is not finished`() {
        val all = listOf(
            book(1, "Saga #1", finished(10)),
            book(2, "Saga #2", finished(20)),
            book(3, "Saga #3"),
            book(4, "Saga #3.5"),
            book(9, "Ohne Nummer"),
        )
        assertEquals(3L to "3", nextInSeries(all[0], all)?.let { it.first.book.id to it.second })
        assertEquals(3L, nextInSeries(all[1], all)?.first?.book?.id)
        assertEquals(4L, nextInSeries(all[2], all)?.first?.book?.id)
        assertNull(nextInSeries(all[3], all))
        assertNull(nextInSeries(all[4], all))
    }

    @Test
    fun `the same volume from a second source counts as finished with the finished one`() {
        val all = listOf(
            book(1, "Saga #1", finished(10)),
            // The same book from a folder on the device, never started there.
            book(5, "Saga #1"),
            book(2, "Saga #2", finished(20)),
            book(6, "Saga #2"),
            book(3, "Saga #3"),
        )

        val series = progressOf(*all.toTypedArray())

        assertEquals(3L, series.next?.book?.id)
        assertEquals(listOf(3L), all.continueSeries().map { it.first.book.id })
        assertEquals(3L, nextInSeries(all[0], all)?.first?.book?.id)
    }

    @Test
    fun `volumes that are not just a number stay in their series and in order`() {
        val all = listOf(
            book(3, "Saga #3"),
            book(21, "Saga #2a, Welt #1"),
            book(2, "Saga #2", finished(10)),
            book(15, "Saga #1,5", finished(5)),
            book(1, "Saga #1-3"),
        )
        val groups = all.seriesGroups()
        assertEquals(listOf("Saga", "Welt"), groups.map { it.name })
        assertEquals(listOf(1L, 15L, 2L, 21L, 3L), groups.first().books.map { it.book.id })
        assertEquals(21L to "2a", nextInSeries(all[2], all)?.let { it.first.book.id to it.second })
        val saga = groups.first().let { seriesProgress(it.name, it.books) }
        assertEquals(21L, saga.next?.book?.id)
        assertEquals("2a", saga.nextVolume)
    }
}
