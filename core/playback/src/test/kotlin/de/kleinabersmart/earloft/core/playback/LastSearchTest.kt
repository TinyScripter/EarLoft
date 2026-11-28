package de.kleinabersmart.earloft.core.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Android Auto search: a new search looks again, its pages don't (review5 P18). */
class LastSearchTest {

    @Test
    fun `searching the same term again finds what a sync brought meanwhile`() = runBlocking {
        val cache = LastSearch<String, List<String>>()
        var library = listOf("old")

        assertEquals(listOf("old"), cache.fresh("rain") { library })
        library = listOf("old", "new")

        assertEquals(listOf("old", "new"), cache.fresh("rain") { library })
    }

    @Test
    fun `the pages of a search reuse its results`() = runBlocking {
        val cache = LastSearch<String, List<String>>()
        var searches = 0
        val search: suspend () -> List<String> = { searches++; listOf("a", "b") }

        cache.fresh("rain", search)
        cache.page("rain", search)
        cache.page("rain", search)
        assertEquals(1, searches)

        // Another term: looked up.
        cache.page("snow", search)
        assertEquals(2, searches)
    }
}
