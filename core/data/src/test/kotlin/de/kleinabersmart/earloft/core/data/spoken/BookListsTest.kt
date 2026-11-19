package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.AuthorSort
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.SeriesSort
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.BookSort
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.LOCAL_BOOK_SERVER_ID
import org.junit.Assert.assertEquals
import org.junit.Test

/** The books' "All" tab: filter and order. */
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
        genres: List<String> = emptyList(),
    ) = BookWithProgress(
        Book(
            id, server, BookKind.BOOK, title, author, series = series, durationMs = durationMs, remoteId = "$id", addedAt = addedAt,
            publishedYear = year, genres = genres,
        ),
        progress,
    )

    private fun progress(positionMs: Long, finished: Boolean = false, updatedAt: Long = 0) =
        BookProgress(positionMs = positionMs, durationMs = 1_000, finished = finished, updatedAt = updatedAt)

    @Test
    fun `the author page groups by series, in volume order, then the books without series`() {
        val books = listOf(
            book(1, "Alone", "A"),
            book(2, "Second", "A", series = "Saga #2"),
            book(3, "First", "A", series = "Saga #1, World #7"),
            book(4, "Beginning", "A", series = "Mountain series #1"),
            book(5, "Middle", "A"),
        )

        val sections = books.seriesSections()

        assertEquals(listOf("Mountain series", "Saga", null), sections.map { it.series })
        assertEquals(listOf(listOf(4L), listOf(3L, 2L), listOf(1L, 5L)), sections.map { s -> s.books.map { it.book.id } })
        assertEquals(listOf(null), listOf(book(1, "Alone", "A")).seriesSections().map { it.series })
        assertEquals(emptyList<SeriesSection>(), emptyList<BookWithProgress>().seriesSections())
    }

    // By title, as the database returns them.
    private val books = listOf(
        book(1, "An old book", "Zora Zeh", addedAt = 10, durationMs = 5_000, year = "1999"),
        book(2, "Book two", "Anna Alt", series = "Saga #2", addedAt = 30, year = "2021", progress = progress(300, updatedAt = 50)),
        book(3, "Book one", "Anna Alt", series = "Saga #1", addedAt = 20, durationMs = 9_000, progress = progress(1_000, finished = true, updatedAt = 70)),
        book(4, "On the device", "Bert Bau", server = LOCAL_BOOK_SERVER_ID, addedAt = 40, year = "2005-03-01"),
    )

    private fun ids(sort: BookSort, filter: BookFilter = BookFilter.ALL, downloaded: Set<Long> = emptySet()) =
        books.sortedAndFiltered(sort, filter, downloaded).map { it.book.id }

    @Test
    fun `sorts by author with series and volume inside, by date, length and year`() {
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BookSort.TITLE))
        assertEquals(listOf(3L, 2L, 4L, 1L), ids(BookSort.AUTHOR))
        assertEquals(listOf(4L, 2L, 3L, 1L), ids(BookSort.RECENTLY_ADDED))
        // Never-started ones last, among themselves in title order.
        assertEquals(listOf(3L, 2L, 1L, 4L), ids(BookSort.RECENTLY_PLAYED))
        assertEquals(listOf(3L, 1L, 2L, 4L), ids(BookSort.DURATION))
        assertEquals(listOf(2L, 4L, 1L, 3L), ids(BookSort.YEAR))
    }

    @Test
    fun `sorts authors and author groups regardless of case and umlauts`() {
        val mixed = listOf(
            book(1, "Z", "Stefan Zweig"),
            book(2, "B", "honoré de Balzac"),
            book(3, "O", "Ödön von Horváth"),
            book(4, "A", "Anna Alt"),
            book(5, "N", ""),
        )

        assertEquals(
            listOf(4L, 2L, 3L, 1L, 5L),
            mixed.sortedAndFiltered(BookSort.AUTHOR, BookFilter.ALL, emptySet()).map { it.book.id },
        )
        assertEquals(
            listOf("Anna Alt", "honoré de Balzac", "Ödön von Horváth", "Stefan Zweig"),
            mixed.authorGroups().map { it.name },
        )
    }

    @Test
    fun `filters by progress and by what is on the device`() {
        assertEquals(listOf(1L, 4L), ids(BookSort.TITLE, BookFilter.NOT_STARTED))
        assertEquals(listOf(2L), ids(BookSort.TITLE, BookFilter.STARTED))
        assertEquals(listOf(3L), ids(BookSort.TITLE, BookFilter.FINISHED))
        assertEquals(listOf(1L, 4L), ids(BookSort.TITLE, BookFilter.DOWNLOADED, downloaded = setOf(1L)))
    }

    @Test
    fun `a genre narrows every filter and has its own group`() {
        val withGenres = listOf(
            book(1, "Crime one", "A", genres = listOf("Crime", "Thriller")),
            book(2, "Crime two", "A", genres = listOf("Crime"), progress = progress(10)),
            book(3, "Fantasy", "B", genres = listOf("Fantasy")),
            book(4, "Without", "B"),
        )
        fun ids(filter: BookFilter, genre: String?) = withGenres.sortedAndFiltered(BookSort.TITLE, filter, emptySet(), genre).map { it.book.id }
        assertEquals(listOf(1L, 2L), ids(BookFilter.ALL, "Crime"))
        assertEquals(listOf(2L), ids(BookFilter.STARTED, "Crime"))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(BookFilter.ALL, null))
        assertEquals(listOf("Crime", "Fantasy", "Thriller"), withGenres.genreGroups().map { it.name })
        assertEquals(listOf(1L, 2L), withGenres.genreGroups().first { it.name == "Crime" }.books.map { it.book.id })
    }

    @Test
    fun `genres in different spellings are one group and one filter`() {
        val spelled = listOf(
            book(1, "Echo", "A", genres = listOf("Crime")),
            book(2, "Zulu", "A", genres = listOf("crime", "Sci-Fi")),
            book(3, "Delta", "B", genres = listOf("Crime", "CRIME")),
            book(4, "Victor", "B", genres = listOf("Sci Fi")),
        )
        val groups = spelled.genreGroups()
        // The most frequent spelling gives the name; a book appears in it only once.
        assertEquals(listOf("Crime", "Sci Fi"), groups.map { it.name })
        assertEquals(listOf(3L, 1L, 2L), groups.first().books.map { it.book.id })
        assertEquals(listOf(4L, 2L), groups.last().books.map { it.book.id })
        val ids = spelled.sortedAndFiltered(BookSort.TITLE, BookFilter.ALL, emptySet(), "crime").map { it.book.id }
        assertEquals(listOf(1L, 2L, 3L), ids)
    }

    @Test
    fun `series by recently played and authors by number of books`() {
        val more = books + listOf(
            book(5, "Short", "Zora Zeh", series = "Anthology #1", progress = progress(10, updatedAt = 90)),
            book(6, "Without", "Bert Bau", series = "Colorful series #1"),
        )
        val series = more.seriesGroups()
        assertEquals(listOf("Anthology", "Colorful series", "Saga"), series.sortedSeries(SeriesSort.NAME).map { it.name })
        // Saga last at 70 (volume one finished), anthology at 90; the never-played series stays last.
        assertEquals(listOf("Anthology", "Saga", "Colorful series"), series.sortedSeries(SeriesSort.RECENTLY_PLAYED).map { it.name })
        val authors = more.authorGroups()
        assertEquals(listOf("Anna Alt", "Bert Bau", "Zora Zeh"), authors.sortedAuthors(AuthorSort.NAME).map { it.name })
        // Equal counts stay sorted by name.
        assertEquals(listOf("Anna Alt", "Bert Bau", "Zora Zeh"), authors.sortedAuthors(AuthorSort.BOOK_COUNT).map { it.name })
        val oneMore = (more + book(7, "One more", "Zora Zeh")).authorGroups()
        assertEquals(listOf("Zora Zeh", "Anna Alt", "Bert Bau"), oneMore.sortedAuthors(AuthorSort.BOOK_COUNT).map { it.name })
    }
}
