package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShakeCounterTest {

    private val counter = ShakeCounter()

    @Test
    fun `drei kraeftige Ausschlaege kurz hintereinander sind ein Schuetteln`() {
        assertFalse(counter.onSample(3f, 0))
        assertFalse(counter.onSample(1f, 100))
        assertFalse(counter.onSample(2.5f, 200))
        assertTrue(counter.onSample(2.8f, 400))
    }

    @Test
    fun `ein Ruck oder ruhiges Liegen ist keins`() {
        assertFalse(counter.onSample(4f, 0))
        repeat(20) { assertFalse(counter.onSample(1.1f, 100L + it * 60)) }
        // Zu weit auseinander.
        assertFalse(counter.onSample(3f, 2_000))
        assertFalse(counter.onSample(3f, 3_000))
        assertFalse(counter.onSample(3f, 4_000))
    }

    @Test
    fun `nach einem Schuetteln zaehlt das naechste erst nach der Pause`() {
        repeat(3) { counter.onSample(3f, it * 100L) }
        repeat(3) { assertFalse(counter.onSample(3f, 500L + it * 100)) }
        assertFalse(counter.onSample(3f, 2_300))
        assertFalse(counter.onSample(3f, 2_400))
        assertTrue(counter.onSample(3f, 2_500))
    }
}
