package de.kleinabersmart.kleinaberlaut.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ListeningMeterTest {

    private var now = 0L
    private val meter = ListeningMeter { now }

    @Test
    fun `an hour of content counts an hour whatever the speed`() {
        meter.start(2f)
        now += 30 * 60_000L

        assertEquals(60 * 60_000L, meter.drain())
    }

    @Test
    fun `pauses do not count`() {
        meter.start(1f)
        now += 10_000
        meter.stop()
        now += 5 * 60_000 // Pause
        meter.start(1f)
        now += 20_000

        assertEquals(30_000L, meter.drain())
    }

    @Test
    fun `a speed change applies from then on and drain keeps measuring`() {
        meter.start(1f)
        now += 10_000
        meter.setSpeed(1.5f)
        now += 10_000
        assertEquals(25_000L, meter.drain())

        now += 10_000
        assertEquals(15_000L, meter.drain())
        meter.stop()
        assertFalse(meter.isRunning)
        now += 10_000
        assertEquals(0L, meter.drain())
    }

    @Test
    fun `fractions of a millisecond are carried over`() {
        meter.start(1.25f)
        now += 1
        assertEquals(1L, meter.drain())
        now += 3 // 3,75 ms, 0,25 ms Rest -> 4 ms
        assertEquals(4L, meter.drain())
    }

    @Test
    fun `starting twice does not reset the measurement`() {
        meter.start(1f)
        now += 5_000
        meter.start(1f)
        now += 5_000

        assertEquals(10_000L, meter.drain())
    }
}
