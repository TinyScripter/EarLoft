package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedRuleTest {

    private val hour = 3_600_000L

    @Test
    fun `a stopped episode counts as heard with 2 percent left`() {
        // 2 % von einer Stunde = 72 s.
        assertTrue(isFinishedAt(hour - 72_000, hour, isEpisode = true, stopped = true))
        assertFalse(isFinishedAt(hour - 73_000, hour, isEpisode = true, stopped = true))
    }

    @Test
    fun `a stopped episode may miss at least 30 s`() {
        val tenMinutes = 600_000L
        assertTrue(isFinishedAt(tenMinutes - 30_000, tenMinutes, isEpisode = true, stopped = true))
        assertFalse(isFinishedAt(tenMinutes - 31_000, tenMinutes, isEpisode = true, stopped = true))
    }

    @Test
    fun `very short episodes miss at most 10 percent`() {
        // „tagesschau in 100 Sekunden“: höchstens 10 s, nicht 30 s.
        assertTrue(isFinishedAt(90_000, 100_000, isEpisode = true, stopped = true))
        assertFalse(isFinishedAt(85_000, 100_000, isEpisode = true, stopped = true))
    }

    @Test
    fun `a playing episode and books keep the 5 s rule`() {
        assertFalse(isFinishedAt(hour - 60_000, hour, isEpisode = true, stopped = false))
        assertTrue(isFinishedAt(hour - 5_000, hour, isEpisode = true, stopped = false))
        assertFalse(isFinishedAt(hour - 60_000, hour, isEpisode = false, stopped = true))
        assertTrue(isFinishedAt(hour - 5_000, hour, isEpisode = false, stopped = true))
    }

    @Test
    fun `nothing is finished without a known duration`() {
        assertFalse(isFinishedAt(0, 0, isEpisode = true, stopped = true))
    }
}
