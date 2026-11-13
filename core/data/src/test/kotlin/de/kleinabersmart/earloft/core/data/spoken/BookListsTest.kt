package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookSort
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/** Reiter „Alle“ der Bücher: Filter und Reihenfolge. */
class BookListsTest {

    private fun book(
        id: Long,
        title: String,
        author: String,
        series: String = "",
        addedAt: Long = 0,
        durationMs: Long = 1_000,
        year: String? = null,
        server: String = "abs",
        progress: BookProgress? = null,
    ) = BookWithProgress(
        Book(id, server, BookKind.BOOK, title, author, series = series, durationMs = durationMs, remoteId = "$id", addedAt = addedAt, publishedYear = year),
        progress,
    )

    private fun progress(positionMs: Long, finished: Boolean = false, updatedAt: Long = 0) =
        BookProgress(positionMs = positionMs, durationMs = 1_000, finished = finished, updatedAt = updatedAt)

    // Nach Titel, wie die Datenbank sie liefert.
    private val books = listOf(
        book(1, "Altes Buch", "Zora Zeh", addedAt = 10, durationMs = 5_000, year = "1999"),
        book(2, "Band zwei", "Anna Alt", series = "Saga #2", addedAt = 30, year = "2021", progress = progress(300, updatedAt = 50)),
        book(3, "Band eins", "Anna Alt", series = "Saga #1", addedAt = 20, durationMs = 9_000, progress = progress(1_000, finished = true, updatedAt = 70)),
        book(4, "Vom Gerät", "Bert Bau", server = LOCAL_BOOK_SERVER_ID, addedAt = 40, year = "2005-03-01"),
    )

    private fun ids(sort: BookSort, filter: BookFilter = BookFilter.ALL, downloaded: Set<Long> = emptySet()) =
        books.sortedAndFiltered(sort, filter, downloaded).map { it.book.id }

    @Test
    fun `sorts by author with series and volume inside, by date, length and year`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BookSort.TITLE))
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(BookSort.AUTHOR))
        assertEquals(listOf(4L, 2L, 3L, 1L), ids(BookSort.RECENTLY_ADDED))
        // Nie gestartete hinten, untereinander in der Reihenfolge nach Titel.
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(BookSort.RECENTLY_PLAYED))
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(BookSort.DURATION))
        assertEquals(listOf(2L, 4L, 1L, 3L), ids(BookSort.YEAR))
    }

    @Test
    fun `filters by progress and by what is on the device`() {
        assertEquals(listOf(1L, 4L), ids(BookSort.TITLE, BookFilter.NOT_STARTED))
        assertEquals(listOf(2L), ids(BookSort.TITLE, BookFilter.STARTED))
        assertEquals(listOf(3L), ids(BookSort.TITLE, BookFilter.FINISHED))
        assertEquals(listOf(1L, 4L), ids(BookSort.TITLE, BookFilter.DOWNLOADED, downloaded = setOf(1L)))
    }
}
