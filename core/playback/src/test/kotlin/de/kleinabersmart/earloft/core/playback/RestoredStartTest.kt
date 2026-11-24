package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoredStartTest {

    private val saved = listOf(10L, 11L, 12L, 13L, 14L)

    @Test
    fun `with all songs there it starts at the saved title and position`() {
        assertEquals(RestoredStart(index = 3, sameSong = true), restoredStart(saved, saved.toSet(), savedIndex = 3))
    }

    @Test
    fun `a song removed before the saved title moves the start by one`() {
        assertEquals(RestoredStart(index = 2, sameSong = true), restoredStart(saved, setOf(10L, 12L, 13L, 14L), savedIndex = 3))
    }

    @Test
    fun `a removed saved title starts the next one from its beginning`() {
        assertEquals(RestoredStart(index = 3, sameSong = false), restoredStart(saved, setOf(10L, 11L, 12L, 14L), savedIndex = 3))
    }

    @Test
    fun `a removed last title starts the one that is last now`() {
        assertEquals(RestoredStart(index = 3, sameSong = false), restoredStart(saved, setOf(10L, 11L, 12L, 13L), savedIndex = 4))
    }

    @Test
    fun `a saved title deleted from the end of the queue starts the one that is last now from its beginning`() {
        // Deleted songs at the end leave no gap: the stored queue is just shorter than the saved index (review3 Q2).
        assertEquals(RestoredStart(index = 1, sameSong = false), restoredStart(listOf(10L, 11L), setOf(10L, 11L), savedIndex = 2))
        assertEquals(RestoredStart(index = 1, sameSong = false), restoredStart(listOf(10L, 11L), setOf(10L, 11L), savedIndex = 4))
    }

    @Test
    fun `an index outside the queue is limited to it, not as the same title`() {
        assertEquals(RestoredStart(index = 4, sameSong = false), restoredStart(saved, saved.toSet(), savedIndex = 9))
        assertEquals(RestoredStart(index = 0, sameSong = true), restoredStart(saved, saved.toSet(), savedIndex = -2))
    }
}
