package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IndexOfSongTest {

    @Test
    fun `finds the song at or after the saved one`() {
        assertEquals(3, indexOfSong(listOf(1, 2, 3, 4, 5), songId = 4, near = 1))
        assertEquals(1, indexOfSong(listOf(1, 2, 3), songId = 2, near = 1))
    }

    @Test
    fun `a song queued twice is found where playback is`() {
        assertEquals(3, indexOfSong(listOf(7, 1, 2, 7, 3), songId = 7, near = 2))
    }

    @Test
    fun `falls back to the nearest one before the saved song`() {
        assertEquals(2, indexOfSong(listOf(1, 5, 5, 3), songId = 5, near = 3))
    }

    @Test
    fun `nothing for a song that is not in the saved queue`() {
        assertNull(indexOfSong(listOf(1, 2, 3), songId = 9, near = 0))
        assertNull(indexOfSong(emptyList(), songId = 9, near = 0))
    }

    @Test
    fun `a saved index beyond the queue still finds the song`() {
        assertEquals(0, indexOfSong(listOf(4, 2), songId = 4, near = 10))
    }
}
