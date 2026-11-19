package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FinishedRuleTest {

    private val hour = 3_600_000L

    @Test
    fun `a stopped episode counts as heard with 2 percent left`() {
        // 2 % of one hour = 72 s.
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
        // "tagesschau in 100 Sekunden": at most 10 s, not 30 s.
        assertTrue(isFinishedAt(90_000, 100_000, isEpisode = true, stopped = true))
        assertFalse(isFinishedAt(85_000, 100_000, isEpisode = true, stopped = true))
    }

    @Test
    fun `playing episodes and books keep the 5 s rule`() {
        assertFalse(isFinishedAt(hour - 60_000, hour, isEpisode = true, stopped = false))
        assertTrue(isFinishedAt(hour - 5_000, hour, isEpisode = true, stopped = false))
        assertFalse(isFinishedAt(hour - 30_000, hour, isEpisode = false, stopped = false))
        assertTrue(isFinishedAt(hour - 5_000, hour, isEpisode = false, stopped = false))
    }

    @Test
    fun `a stopped book counts as finished from 99 percent`() {
        // 1 % of ten hours = 6 min.
        val tenHours = 10 * hour
        assertTrue(isFinishedAt(tenHours - 360_000, tenHours, isEpisode = false, stopped = true))
        assertFalse(isFinishedAt(tenHours - 361_000, tenHours, isEpisode = false, stopped = true))
    }

    @Test
    fun `a stopped book with chapters must be in its last chapter`() {
        val tenHours = 10 * hour
        // Final chapter of 2 min: 4 min before the end you are still in the second to last one.
        assertFalse(isFinishedAt(tenHours - 240_000, tenHours, isEpisode = false, stopped = true, lastChapterStartMs = tenHours - 120_000))
        assertTrue(isFinishedAt(tenHours - 100_000, tenHours, isEpisode = false, stopped = true, lastChapterStartMs = tenHours - 120_000))
        // Long final chapter: the 99 % count.
        assertTrue(isFinishedAt(tenHours - 300_000, tenHours, isEpisode = false, stopped = true, lastChapterStartMs = tenHours - hour))
    }

    @Test
    fun `nothing is finished without a known duration`() {
        assertFalse(isFinishedAt(0, 0, isEpisode = true, stopped = true))
    }
}
