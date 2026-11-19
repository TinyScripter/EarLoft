package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeCounterTest {

    private val counter = ShakeCounter()

    @Test
    fun `three strong peaks in quick succession are a shake`() {
        assertFalse(counter.onSample(3f, 0))
        assertFalse(counter.onSample(1f, 100))
        assertFalse(counter.onSample(2.5f, 200))
        assertTrue(counter.onSample(2.8f, 400))
    }

    @Test
    fun `a single jolt or lying still is none`() {
        assertFalse(counter.onSample(4f, 0))
        repeat(20) { assertFalse(counter.onSample(1.1f, 100L + it * 60)) }
        // Too far apart.
        assertFalse(counter.onSample(3f, 2_000))
        assertFalse(counter.onSample(3f, 3_000))
        assertFalse(counter.onSample(3f, 4_000))
    }

    @Test
    fun `after a shake the next one only counts after the pause`() {
        repeat(3) { counter.onSample(3f, it * 100L) }
        repeat(3) { assertFalse(counter.onSample(3f, 500L + it * 100)) }
        assertFalse(counter.onSample(3f, 2_300))
        assertFalse(counter.onSample(3f, 2_400))
        assertTrue(counter.onSample(3f, 2_500))
    }
}
