package keinabersmart.kleinaberlaut.ui.search

import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.AuthorHit
import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.SeriesHit
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.SearchResults
import keinabersmart.kleinaberlaut.core.model.SpokenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchScopeTest {

    @Test
    fun `search all ignores the mode`() {
        assertEquals(SearchScope.ALL, SearchScope.of(searchAll = true, mode = SpokenMode.MUSIC))
        assertEquals(SearchScope.ALL, SearchScope.of(searchAll = true, mode = SpokenMode.BOOKS))
        assertEquals(SearchScope.ALL, SearchScope.of(searchAll = true, mode = SpokenMode.PODCASTS))
    }

    @Test
    fun `without search all the mode decides`() {
        assertEquals(SearchScope.MUSIC, SearchScope.of(searchAll = false, mode = SpokenMode.MUSIC))
        assertEquals(SearchScope.BOOKS, SearchScope.of(searchAll = false, mode = SpokenMode.BOOKS))
        assertEquals(SearchScope.PODCASTS, SearchScope.of(searchAll = false, mode = SpokenMode.PODCASTS))
    }

    @Test
    fun `scope selects the sources`() {
        assertTrue(SearchScope.ALL.music && SearchScope.ALL.podcasts && SearchScope.ALL.books)
        assertTrue(SearchScope.MUSIC.music && !SearchScope.MUSIC.podcasts && !SearchScope.MUSIC.books)
        assertTrue(!SearchScope.PODCASTS.music && SearchScope.PODCASTS.podcasts && !SearchScope.PODCASTS.books)
        assertTrue(!SearchScope.BOOKS.music && !SearchScope.BOOKS.podcasts && SearchScope.BOOKS.books)
    }

    @Test
    fun `every scope has its own placeholder`() {
        assertEquals(SearchScope.entries.size, SearchScope.entries.map { it.hint }.toSet().size)
        assertEquals(R.string.search_hint_all, SearchScope.ALL.hint)
        assertEquals(R.string.search_hint, SearchScope.MUSIC.hint)
        assertEquals(R.string.search_hint_podcasts, SearchScope.PODCASTS.hint)
        assertEquals(R.string.search_hint_books, SearchScope.BOOKS.hint)
    }

    @Test
    fun `the mode switch changes the placeholder only while not searching everywhere`() {
        for (mode in SpokenMode.entries) {
            assertEquals(R.string.search_hint_all, SearchScope.of(searchAll = true, mode = mode).hint)
        }
        assertEquals(R.string.search_hint, SearchScope.of(searchAll = false, mode = SpokenMode.MUSIC).hint)
        assertEquals(R.string.search_hint_podcasts, SearchScope.of(searchAll = false, mode = SpokenMode.PODCASTS).hint)
        assertEquals(R.string.search_hint_books, SearchScope.of(searchAll = false, mode = SpokenMode.BOOKS).hint)
    }

    @Test
    fun `state is empty only without music, episode and book hits`() {
        assertTrue(SearchUiState(query = "x").isEmpty)
        val book = BookWithProgress(
            Book(id = 1, serverId = "a", kind = BookKind.BOOK, title = "t", author = "a", durationMs = 1, remoteId = "r"),
            progress = null,
        )
        assertFalse(SearchUiState(query = "x", books = listOf(book)).isEmpty)
        assertFalse(SearchUiState(query = "x", episodes = listOf(book)).isEmpty)
        assertFalse(SearchUiState(query = "x", series = listOf(SeriesHit("s", 1, 1))).isEmpty)
        assertFalse(SearchUiState(query = "x", authors = listOf(AuthorHit("a", 1, 1))).isEmpty)
        assertEquals(SearchResults.EMPTY, SearchUiState().results)
    }
}
