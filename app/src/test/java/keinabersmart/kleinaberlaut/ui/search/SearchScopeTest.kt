package keinabersmart.kleinaberlaut.ui.search

import keinabersmart.kleinaberlaut.core.model.Book
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
        assertEquals(SearchScope.ALL, SearchScope.of(searchAll = true, mode = SpokenMode.SPOKEN))
    }

    @Test
    fun `without search all the mode decides`() {
        assertEquals(SearchScope.MUSIC, SearchScope.of(searchAll = false, mode = SpokenMode.MUSIC))
        assertEquals(SearchScope.SPOKEN, SearchScope.of(searchAll = false, mode = SpokenMode.SPOKEN))
    }

    @Test
    fun `scope selects the sources`() {
        assertTrue(SearchScope.ALL.music && SearchScope.ALL.books)
        assertTrue(SearchScope.MUSIC.music && !SearchScope.MUSIC.books)
        assertTrue(!SearchScope.SPOKEN.music && SearchScope.SPOKEN.books)
    }

    @Test
    fun `state is empty only without music and book hits`() {
        assertTrue(SearchUiState(query = "x").isEmpty)
        val book = BookWithProgress(
            Book(id = 1, serverId = "a", kind = BookKind.BOOK, title = "t", author = "a", durationMs = 1, remoteId = "r"),
            progress = null,
        )
        assertFalse(SearchUiState(query = "x", books = listOf(book)).isEmpty)
        assertEquals(SearchResults.EMPTY, SearchUiState().results)
    }
}
