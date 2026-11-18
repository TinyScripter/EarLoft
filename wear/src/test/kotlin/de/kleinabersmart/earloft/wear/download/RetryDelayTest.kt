package de.kleinabersmart.earloft.wear.download

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryDelayTest {

    @Test
    fun `the delay doubles from 30 seconds like the former WorkManager backoff`() {
        assertEquals(30_000L, retryDelayMs(1))
        assertEquals(60_000L, retryDelayMs(2))
        assertEquals(120_000L, retryDelayMs(3))
        assertEquals(240_000L, retryDelayMs(4))
        assertEquals(8 * 60_000L, retryDelayMs(5))
        assertEquals(16 * 60_000L, retryDelayMs(6))
    }

    @Test
    fun `the delay stops at five hours and never overflows`() {
        val fiveHours = 5 * 60 * 60 * 1000L
        assertEquals(fiveHours, retryDelayMs(11))
        assertEquals(fiveHours, retryDelayMs(30))
        assertEquals(fiveHours, retryDelayMs(Int.MAX_VALUE))
    }

    @Test
    fun `a count below one is treated as the first retry`() {
        assertEquals(30_000L, retryDelayMs(0))
        assertEquals(30_000L, retryDelayMs(-3))
    }
}
