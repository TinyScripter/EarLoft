package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.data.sync.SweepGuard.Companion.CONFIRM_AFTER_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class SweepGuardTest {

    @Test
    fun `a small drop sweeps right away and clears a pending drop`() {
        assertEquals(true to null, SweepGuard.decide(known = 10, listed = 5, pendingSince = null, nowMs = 1_000))
        assertEquals(true to null, SweepGuard.decide(known = 10, listed = 9, pendingSince = 500, nowMs = 1_000))
    }

    @Test
    fun `an empty library never counts as a drop`() {
        assertEquals(true to null, SweepGuard.decide(known = 0, listed = 0, pendingSince = null, nowMs = 1_000))
    }

    @Test
    fun `a drop to nothing or by more than half waits for a later scan`() {
        assertEquals(false to 1_000L, SweepGuard.decide(known = 10, listed = 0, pendingSince = null, nowMs = 1_000))
        assertEquals(false to 1_000L, SweepGuard.decide(known = 10, listed = 4, pendingSince = null, nowMs = 1_000))
    }

    @Test
    fun `a scan right after the first confirms nothing, a later one does`() {
        assertEquals(false to 1_000L, SweepGuard.decide(10, 0, pendingSince = 1_000, nowMs = 1_000 + CONFIRM_AFTER_MS - 1))
        assertEquals(true to null, SweepGuard.decide(10, 0, pendingSince = 1_000, nowMs = 1_000 + CONFIRM_AFTER_MS))
    }
}
