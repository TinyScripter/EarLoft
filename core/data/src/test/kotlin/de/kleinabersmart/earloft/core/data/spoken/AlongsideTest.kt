package de.kleinabersmart.earloft.core.data.spoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Beim Öffnen der App warten die Server nicht auf die Dateien vom Gerät (Review F23). */
class AlongsideTest {

    @Test
    fun `the main work runs while the work alongside still hangs`() = runTest {
        val serversDone = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        // Die Dateien vom Gerät werden erst fertig, wenn die Server schon abgeglichen sind: sonst hängt der Test.
        alongside({ serversDone.await(); order += "local books" }, { order += "local podcasts" }) {
            order += "servers"
            serversDone.complete(Unit)
        }

        assertEquals(setOf("local books", "local podcasts", "servers"), order.toSet())
        assertTrue(order.indexOf("servers") < order.indexOf("local books"))
    }

    @Test
    fun `it ends only once the work alongside is done`() = runTest {
        val localDone = CompletableDeferred<Unit>()
        val run = async { alongside({ localDone.await() }) {} }
        testScheduler.advanceUntilIdle()

        assertFalse(run.isCompleted)
        localDone.complete(Unit)
        run.await()
    }
}
