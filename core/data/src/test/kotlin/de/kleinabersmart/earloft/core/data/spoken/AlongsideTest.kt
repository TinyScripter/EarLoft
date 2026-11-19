package de.kleinabersmart.earloft.core.data.spoken

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When opening the app the servers don't wait for the files from the device (review F23). */
class AlongsideTest {

    @Test
    fun `the main work runs while the work alongside still hangs`() = runTest {
        val serversDone = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        // The files from the device only finish once the servers have been synced: otherwise the test hangs.
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
