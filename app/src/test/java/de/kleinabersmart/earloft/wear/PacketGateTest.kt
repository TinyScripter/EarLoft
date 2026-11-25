package de.kleinabersmart.earloft.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PacketGateTest {

    @Test
    fun `an item another path is importing right now is skipped`() = runTest {
        val gate = PacketGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var runs = 0
        val first = launch {
            gate.once("a", { true }) {
                runs++
                started.complete(Unit)
                release.await()
            }
        }
        started.await()

        gate.once("a", { true }) { runs++ }

        assertEquals(1, runs)
        release.complete(Unit)
        first.join()
    }

    @Test
    fun `an item the other path has imported and deleted is not imported again`() = runTest {
        val gate = PacketGate()
        val deleted = mutableSetOf<String>()
        var runs = 0

        // The event path imports and deletes; the catch-up comes to the same item afterwards, long after the importers forgot it.
        gate.once("a", { "a" !in deleted }) {
            runs++
            deleted += "a"
        }
        gate.once("a", { "a" !in deleted }) { runs++ }

        assertEquals(1, runs)
    }

    @Test
    fun `other items are not held back and the claim is released after a failure`() = runTest {
        val gate = PacketGate()
        var runs = 0

        runCatching { gate.once("a", { true }) { error("import failed") } }
        gate.once("a", { true }) { runs++ }
        gate.once("b", { true }) { runs++ }

        assertEquals(2, runs)
    }
}
