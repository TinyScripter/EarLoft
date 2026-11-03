package keinabersmart.kleinaberlaut.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class BookClockTextTest {

    @Test
    fun `position and total are shown as hours and minutes`() {
        assertEquals("0:23 / 1:02 h", bookClockText(positionMs = (23 * 60 + 40) * 1000L, durationMs = (3600 + 2 * 60 + 43) * 1000L))
    }

    @Test
    fun `long books keep counting hours and the start is zero`() {
        assertEquals("0:00 / 12:00 h", bookClockText(0, 12 * 3600 * 1000L))
        assertEquals("10:05 / 25:00 h", bookClockText((10 * 3600 + 5 * 60 + 9) * 1000L, 25 * 3600 * 1000L))
    }

    @Test
    fun `an unknown total shows only the position and partial minutes are cut`() {
        assertEquals("0:00 h", bookClockText(59_999, 0))
        assertEquals("0:00 / 0:01 h", bookClockText(-5, 60_000))
    }

    @Test
    fun `the next update comes at the next full minute of book time`() {
        // 20 s vor der vollen Minute: bei 1,0 nach 20 s, bei 2,0 schon nach 10 s (plus Reserve).
        assertEquals(20_200L, msUntilNextMinute(positionMs = 100_000, speed = 1f))
        assertEquals(10_200L, msUntilNextMinute(positionMs = 100_000, speed = 2f))
        // Genau auf der Minute: eine ganze Minute warten.
        assertEquals(60_200L, msUntilNextMinute(positionMs = 120_000, speed = 1f))
    }
}
