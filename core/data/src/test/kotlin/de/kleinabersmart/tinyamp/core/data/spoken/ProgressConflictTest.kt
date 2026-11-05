package de.kleinabersmart.tinyamp.core.data.spoken

import de.kleinabersmart.tinyamp.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressConflictTest {

    private fun progress(positionMs: Long, updatedAt: Long, dirty: Boolean = false) =
        BookProgress(positionMs, durationMs = 3_600_000, finished = false, updatedAt = updatedAt, dirty = dirty)

    @Test
    fun `server wins when the book has no local progress yet`() {
        assertEquals(ProgressDecision.TakeRemote, ProgressConflict.resolve(null, progress(1_000, 5)))
    }

    @Test
    fun `newer server change wins`() {
        assertEquals(ProgressDecision.TakeRemote, ProgressConflict.resolve(progress(1_000, 5), progress(9_000, 6)))
    }

    @Test
    fun `newer local change stays, also when not yet sent`() {
        assertEquals(ProgressDecision.KeepLocal, ProgressConflict.resolve(progress(9_000, 8, dirty = true), progress(1_000, 6)))
        assertEquals(ProgressDecision.KeepLocal, ProgressConflict.resolve(progress(9_000, 8), progress(1_000, 6)))
    }

    @Test
    fun `equal timestamps keep the local state`() {
        assertEquals(ProgressDecision.KeepLocal, ProgressConflict.resolve(progress(9_000, 8), progress(1_000, 8)))
    }

    @Test
    fun `only jumps of more than five seconds are noticeable`() {
        assertFalse(ProgressConflict.isNoticeable(progress(10_000, 1), progress(14_000, 2)))
        assertTrue(ProgressConflict.isNoticeable(progress(10_000, 1), progress(16_000, 2)))
        assertFalse(ProgressConflict.isNoticeable(null, progress(16_000, 2)))
    }
}
