package keinabersmart.kleinaberlaut.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookClockTest {

    @Test
    fun `a running clock counts down until it is stopped`() {
        val running = BookClock(bookId = 1, remainingMs = 60_000, running = true, measuredAtMs = 1_000)
        assertEquals(BookClock(bookId = 1, remainingMs = 45_000, running = false, measuredAtMs = 16_000), running.stoppedAt(16_000))
        assertEquals(0L, running.stoppedAt(100_000).remainingMs)
    }

    @Test
    fun `a stopped clock stays as it is`() {
        val stopped = BookClock(bookId = 1, remainingMs = 60_000, running = false, measuredAtMs = 1_000)
        assertSame(stopped, stopped.stoppedAt(16_000))
    }
}
