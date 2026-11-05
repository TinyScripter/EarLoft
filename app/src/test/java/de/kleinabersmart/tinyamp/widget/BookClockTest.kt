package de.kleinabersmart.tinyamp.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookClockTest {

    private fun clock(positionMs: Long, speed: Float = 1f, running: Boolean = true, durationMs: Long = 3_600_000) =
        BookClock(bookId = 1, positionMs = positionMs, durationMs = durationMs, speed = speed, running = running, measuredAtMs = 1_000)

    @Test
    fun `played and total time are shown with two-digit hours`() {
        assertEquals("00:45:12 / 03:12:01", bookClockText((45 * 60 + 12) * 1000L + 999, (3 * 3600 + 12 * 60 + 1) * 1000L))
        assertEquals("00:00:00 / 12:00:00", bookClockText(-5, 12 * 3600 * 1000L))
        assertEquals("105:00:00", clockText(105 * 3600 * 1000L))
    }

    @Test
    fun `an unknown total shows only the played time`() {
        assertEquals("01:02:03", bookClockText((3600 + 2 * 60 + 3) * 1000L, 0))
    }

    @Test
    fun `the chronometer prefix pads its text to two-digit hours`() {
        // Der Chronometer zeigt „45:12“ bzw. „3:12:01“ bzw. „12:00:00“.
        assertEquals("00:", chronometerPrefix(45 * 60 * 1000L))
        assertEquals("0", chronometerPrefix(3_600_000))
        assertEquals("", chronometerPrefix(10 * 3_600_000L))
    }

    @Test
    fun `only at normal speed the widget counts by itself`() {
        assertTrue(clock(0).ticksItself)
        assertFalse(clock(0, speed = 1.5f).ticksItself)
        assertFalse(clock(0, running = false).ticksItself)
    }

    @Test
    fun `a running clock advances in book time until it is stopped`() {
        assertEquals(clock(positionMs = 25_000, speed = 1.5f, running = false).copy(measuredAtMs = 11_000), clock(10_000, speed = 1.5f).stoppedAt(11_000))
        assertEquals(3_600_000L, clock(3_599_000).stoppedAt(100_000).positionMs)
        val stopped = clock(10_000, running = false)
        assertSame(stopped, stopped.stoppedAt(11_000))
    }

    @Test
    fun `between measurements the position is extrapolated at the current speed`() {
        assertEquals(clock(positionMs = 11_500, speed = 1.5f).copy(measuredAtMs = 2_000), clock(10_000, speed = 1.5f).at(2_000))
        val stopped = clock(10_000, running = false)
        assertSame(stopped, stopped.at(2_000))
    }

    @Test
    fun `the chronometer is measured again when the hours get another digit`() {
        assertEquals(60_020L, clock(3_540_000).msUntilPrefixChange())
        assertEquals(9 * 3_600_000L + 20, clock(3_600_000).msUntilPrefixChange())
        assertNull(clock(10 * 3_600_000L).msUntilPrefixChange())
    }
}
