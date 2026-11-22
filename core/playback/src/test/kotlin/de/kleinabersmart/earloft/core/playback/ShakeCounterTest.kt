package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeCounterTest {

    private val counter = ShakeCounter()

    /** A spike at [timeMs] and back below the threshold 60 ms later; `true` if the spike was a shake. */
    private fun spike(timeMs: Long, g: Float = 3f): Boolean {
        val shake = counter.onSample(g, timeMs)
        counter.onSample(1f, timeMs + 60)
        return shake
    }

    @Test
    fun `three strong peaks in quick succession are a shake`() {
        assertFalse(counter.onSample(3f, 0))
        assertFalse(counter.onSample(1f, 100))
        assertFalse(counter.onSample(2.5f, 200))
        assertFalse(counter.onSample(1.2f, 300))
        assertTrue(counter.onSample(2.8f, 400))
    }

    @Test
    fun `a single jolt or lying still is none`() {
        assertFalse(counter.onSample(4f, 0))
        repeat(20) { assertFalse(counter.onSample(1.1f, 100L + it * 60)) }
        // Too far apart.
        assertFalse(spike(2_000))
        assertFalse(spike(3_000))
        assertFalse(spike(4_000))
    }

    @Test
    fun `a knock with several measurements above the threshold is one spike`() {
        // About 200 ms over 2.2 g at the sensor's rate: four measurements, one spike.
        assertFalse(counter.onSample(2.6f, 0))
        assertFalse(counter.onSample(3.4f, 60))
        assertFalse(counter.onSample(2.9f, 120))
        assertFalse(counter.onSample(2.3f, 180))
        assertFalse(counter.onSample(1f, 240))
    }

    @Test
    fun `after a shake the next one only counts after the pause`() {
        assertFalse(spike(0))
        assertFalse(spike(150))
        assertTrue(spike(300))
        repeat(3) { assertFalse(spike(600L + it * 150)) }
        assertFalse(spike(2_400))
        assertFalse(spike(2_550))
        assertTrue(spike(2_700))
    }
}
