package de.kleinabersmart.kleinaberlaut.core.data.spoken

import de.kleinabersmart.kleinaberlaut.core.model.Book
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.SeriesHit
import org.junit.Assert.assertEquals
import org.junit.Test

class SeriesSearchTest {

    private fun book(id: Long, title: String, series: String) = Book(
        id = id, serverId = "abs", kind = BookKind.BOOK, title = title, author = "A", series = series, durationMs = 1, remoteId = "r$id",
    )

    @Test
    fun `series are found by name and counted, the lowest volume gives the cover`() {
        val books = listOf(
            book(1, "Zweiter Teil", "Carl Mørck #2"),
            book(2, "Erster Teil", "Carl Mørck #1"),
            book(3, "Einzelband", ""),
        )

        assertEquals(listOf(SeriesHit("Carl Mørck", firstBookId = 2, bookCount = 2)), seriesHits(books, "carl", limit = 10))
    }

    @Test
    fun `the volume number is not part of the name and other series of a book are ignored`() {
        val books = listOf(book(1, "Buch", "Alpha #1, Beta #4"))

        assertEquals(emptyList<SeriesHit>(), seriesHits(books, "#1", limit = 10))
        assertEquals(listOf(SeriesHit("Beta", 1, 1)), seriesHits(books, "beta", limit = 10))
    }

    @Test
    fun `a book in several matching series counts for each, results are sorted by name and limited`() {
        val books = listOf(
            book(1, "Buch", "Zeta Saga #1, Alpha Saga #3"),
            book(2, "Anderes", "Alpha Saga #1"),
        )

        assertEquals(listOf("Alpha Saga", "Zeta Saga"), seriesHits(books, "saga", limit = 10).map { it.name })
        assertEquals(listOf("Alpha Saga"), seriesHits(books, "saga", limit = 1).map { it.name })
        assertEquals(2, seriesHits(books, "saga", limit = 10).first().bookCount)
        assertEquals(2, seriesHits(books, "saga", limit = 10).first().firstBookId)
    }

    @Test
    fun `an empty search term finds nothing`() {
        assertEquals(emptyList<SeriesHit>(), seriesHits(listOf(book(1, "Buch", "Alpha #1")), "", limit = 10))
    }
}
