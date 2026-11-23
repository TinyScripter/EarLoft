package de.kleinabersmart.earloft.core.playback

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stream cache entries go once song sources disappear (review2 J34). */
class StaleStreamCacheKeysTest {

    private val library = setOf("nd1:song-a", "jf2:0f3e")
    private val asked = mutableListOf<Collection<String>>()

    private suspend fun stale(vararg keys: String): List<String> =
        staleStreamCacheKeys(keys.toList()) { uids -> asked += uids; uids.filterTo(HashSet()) { it in library } }

    @Test
    fun `the stream of a removed song goes, an existing one stays`() = runTest {
        assertEquals(listOf("nd1:gone"), stale("nd1:song-a", "nd1:gone"))
    }

    @Test
    fun `books and episodes are never song sources and stay`() = runTest {
        assertEquals(listOf("nd1:gone"), stale("book:7:li_123:ino_456", "book:12:ep-9:0", "nd1:gone"))
        assertTrue(asked.flatten().none { it.startsWith("book:") })
    }

    @Test
    fun `a transcoded stream stays as long as its song does`() = runTest {
        assertEquals(
            listOf("nd1:gone:opus-96"),
            stale("nd1:song-a:mp3-128", "jf2:0f3e:aac-256", "nd1:gone:opus-96"),
        )
    }

    @Test
    fun `only books in the cache asks nothing`() = runTest {
        assertEquals(emptyList<String>(), stale("book:7:li_123:ino_456"))
        assertTrue(asked.isEmpty())
    }
}
