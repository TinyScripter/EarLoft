package de.kleinabersmart.earloft.core.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProgressThrottleTest {

    private var now = 10_000L
    private val throttle = ProgressThrottle(intervalMs = 1_000, clock = { now })

    @Test
    fun `first report immediately, then at most once per interval`() {
        assertEquals(0, throttle.next(0, 1_000))
        now += 100
        assertNull(throttle.next(50, 1_000))
        now += 899
        assertNull(throttle.next(80, 1_000))
        now += 1
        assertEquals(9, throttle.next(90, 1_000))
    }

    @Test
    fun `the same percentage is not repeated`() {
        assertEquals(1, throttle.next(10, 1_000))
        now += 5_000
        assertNull(throttle.next(19, 1_000))
        assertEquals(2, throttle.next(20, 1_000))
    }

    @Test
    fun `without a known size nothing new, the start already shows indeterminate progress`() {
        assertNull(throttle.next(100, null))
        now += 5_000
        assertNull(throttle.next(300, 0))
        assertEquals(5, throttle.next(50, 1_000))
        now += 5_000
        assertEquals(ProgressThrottle.UNKNOWN, throttle.next(200, null))
    }
}
