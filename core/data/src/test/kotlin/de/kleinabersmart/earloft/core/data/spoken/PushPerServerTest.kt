package de.kleinabersmart.earloft.core.data.spoken

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An unreachable server costs one timeout per sync, not one per pending entry (review5 P30). */
class PushPerServerTest {

    private val entries = listOf("home:1", "home:2", "away:1", "home:3", "away:2", "none")

    private fun serverOf(entry: String) = entry.substringBefore(':', "").ifEmpty { null }

    @Test
    fun `after a failure the rest of that server waits, the others go out`() = runBlocking {
        val tried = mutableListOf<String>()

        val done = pushPerServer(entries, serverId = null, serverOf = { serverOf(it) }) { tried += it; !it.startsWith("home") }

        assertFalse(done)
        assertEquals(listOf("home:1", "away:1", "away:2", "none"), tried)
    }

    @Test
    fun `only the entries of the given server`() = runBlocking {
        val tried = mutableListOf<String>()

        assertTrue(pushPerServer(entries, serverId = "away", serverOf = { serverOf(it) }) { tried += it; true })

        assertEquals(listOf("away:1", "away:2"), tried)
    }
}
