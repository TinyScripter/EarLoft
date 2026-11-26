package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** Where the remote control's position starts, with and without the phone (review4 T59, 8.6 Y30). */
class RemotePositionTest {

    /** Reported at 10:00:00 at 60 s, playing, a 10 min track. */
    private val report = WearRemoteStateDto(hasMedia = true, durationMs = 600_000, positionMs = 60_000, sampledAt = 1_000_000, isPlaying = true)

    @Test
    fun `with the phone the position counts on from its report`() {
        assertEquals(90_000L, startPosition(report, reachable = true, now = 1_030_000, shown = null))
    }

    @Test
    fun `when the phone goes away the position stays where it was shown`() {
        // Shown counted on to 3:30; before, it jumped back to the report's 1:00, then forward again once the phone was back.
        assertEquals(210_000L, startPosition(report, reachable = false, now = 1_150_000, shown = ShownPosition(report, 210_000)))
    }

    @Test
    fun `without the phone and nothing shown for this report, the report applies`() {
        val older = report.copy(positionMs = 5_000)
        assertEquals(60_000L, startPosition(report, reachable = false, now = 1_150_000, shown = ShownPosition(older, 210_000)))
        assertEquals(0L, startPosition(null, reachable = false, now = 1_150_000, shown = null))
    }
}
