package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
import java.io.IOException
import java.net.SocketTimeoutException
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
    fun `after an unreachable server the rest of that server waits, the others go out`() = runBlocking {
        val tried = mutableListOf<String>()

        val done = pushPerServer(entries, serverId = null, serverOf = { serverOf(it) }) {
            tried += it
            if (it.startsWith("home")) Pushed.UNREACHABLE else Pushed.DONE
        }

        assertFalse(done)
        assertEquals(listOf("home:1", "away:1", "away:2", "none"), tried)
    }

    @Test
    fun `an entry the server rejects holds back nothing else of that server`() = runBlocking {
        val tried = mutableListOf<String>()

        val done = pushPerServer(entries, serverId = null, serverOf = { serverOf(it) }) {
            tried += it
            if (it == "home:1") Pushed.REJECTED else Pushed.DONE
        }

        assertFalse(done)
        assertEquals(entries, tried)
    }

    @Test
    fun `only the entries of the given server`() = runBlocking {
        val tried = mutableListOf<String>()

        assertTrue(pushPerServer(entries, serverId = "away", serverOf = { serverOf(it) }) { tried += it; Pushed.DONE })

        assertEquals(listOf("away:1", "away:2"), tried)
    }

    @Test
    fun `an answer from the server rejects only the entry, no answer counts as unreachable`() {
        assertEquals(Pushed.REJECTED, AbsException(404, "HTTP 404").pushFailure())
        assertEquals(Pushed.UNREACHABLE, SocketTimeoutException().pushFailure())
        assertEquals(Pushed.UNREACHABLE, IOException("reset").pushFailure())
    }
}
