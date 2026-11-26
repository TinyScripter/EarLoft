package de.kleinabersmart.earloft.wear.ui

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RingSeekTest {

    @Test
    fun `start and end of the ring`() {
        assertEquals(0f, ringProgress(295f), 0.001f)
        assertEquals(1f, ringProgress(245f), 0.001f)
    }

    @Test
    fun `points on the ring clockwise`() {
        assertEquals(0.2097f, ringProgress(0f), 0.001f) // three o'clock
        assertEquals(0.5f, ringProgress(90f), 0.001f) // six o'clock
        assertEquals(0.7903f, ringProgress(180f), 0.001f) // nine o'clock
    }

    @Test
    fun `without a previous value the gap at the top belongs to the nearer side`() {
        assertEquals(1f, ringProgress(255f), 0.001f)
        assertEquals(0f, ringProgress(285f), 0.001f)
    }

    @Test
    fun `in the gap dragging stays on the side it came from`() {
        assertEquals(1f, ringProgress(270f, previous = 0.9f), 0.001f)
        assertEquals(0f, ringProgress(270f, previous = 0.1f), 0.001f)
    }

    @Test
    fun `a jump across the gap does not become a jump in the song`() {
        // The finger drags from 95 % to just past the gap (2 %): stay at the end instead of jumping to the start.
        assertEquals(1f, ringProgress(301.2f, previous = 0.95f), 0.001f)
        assertEquals(0f, ringProgress(240f, previous = 0.05f), 0.001f)
    }

    @Test
    fun `normal movement follows the finger`() {
        assertEquals(0.5f, ringProgress(90f, previous = 0.45f), 0.001f)
    }

    @Test
    fun `a mostly rightward movement is the back swipe, never seeking`() {
        assertTrue(isBackSwipe(Offset(30f, 0f)))
        assertTrue(isBackSwipe(Offset(30f, -20f)))
        assertFalse(isBackSwipe(Offset(20f, 30f)))
        assertFalse(isBackSwipe(Offset(-30f, 0f)))
    }

    @Test
    fun `time is formatted as minutes and seconds`() {
        assertEquals("0:00", formatPosition(0))
        assertEquals("1:05", formatPosition(65_000))
        assertEquals("1:02:05", formatPosition(3_725_000))
        assertEquals("0:00", formatPosition(-5_000))
    }
}
