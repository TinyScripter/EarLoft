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
    fun `an index outside the queue is limited to it`() {
        assertEquals(RestoredStart(index = 4, sameSong = true), restoredStart(saved, saved.toSet(), savedIndex = 9))
        assertEquals(RestoredStart(index = 0, sameSong = true), restoredStart(saved, saved.toSet(), savedIndex = -2))
    }
}
