package keinabersmart.kleinaberlaut.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class BookClockTextTest {

    @Test
    fun `position and total are shown as hours, minutes and seconds`() {
        assertEquals("00:23:40 / 01:02:43", bookClockText(positionMs = (23 * 60 + 40) * 1000L, durationMs = (3600 + 2 * 60 + 43) * 1000L))
    }

    @Test
    fun `long books keep counting hours and the start is all zeros`() {
        assertEquals("00:00:00 / 12:00:00", bookClockText(0, 12 * 3600 * 1000L))
        assertEquals("10:05:09 / 25:00:00", bookClockText((10 * 3600 + 5 * 60 + 9) * 1000L, 25 * 3600 * 1000L))
    }

    @Test
    fun `an unknown total shows only the position and partial seconds are cut`() {
        assertEquals("00:00:59", bookClockText(59_999, 0))
        assertEquals("00:00:00 / 00:01:00", bookClockText(-5, 60_000))
    }
}
