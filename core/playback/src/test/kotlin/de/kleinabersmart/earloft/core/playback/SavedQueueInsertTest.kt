package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class SavedQueueInsertTest {

    @Test
    fun `play next goes right behind the saved song`() {
        assertEquals(listOf(1L, 2L, 10L, 11L, 3L), insertIntoSavedQueue(listOf(1, 2, 3), savedIndex = 1, songIds = listOf(10, 11), next = true))
    }

    @Test
    fun `play next behind the last saved song appends`() {
        assertEquals(listOf(1L, 2L, 10L), insertIntoSavedQueue(listOf(1, 2), savedIndex = 1, songIds = listOf(10), next = true))
    }

    @Test
    fun `add to queue appends regardless of the saved song`() {
        assertEquals(listOf(1L, 2L, 3L, 10L), insertIntoSavedQueue(listOf(1, 2, 3), savedIndex = 0, songIds = listOf(10), next = false))
    }

    @Test
    fun `an empty saved queue becomes the new songs`() {
        assertEquals(listOf(10L, 11L), insertIntoSavedQueue(emptyList(), savedIndex = 4, songIds = listOf(10, 11), next = true))
        assertEquals(listOf(10L), insertIntoSavedQueue(emptyList(), savedIndex = 0, songIds = listOf(10), next = false))
    }

    @Test
    fun `a saved title deleted from the end keeps its place as a gap, the new songs come after it`() {
        // Otherwise the new song took the saved index and started at the deleted title's position (review3 Q2).
        assertEquals(listOf(1L, 2L, GAP, 10L), insertIntoSavedQueue(listOf(1, 2), savedIndex = 2, songIds = listOf(10), next = true))
        assertEquals(listOf(1L, 2L, GAP, 10L), insertIntoSavedQueue(listOf(1, 2), savedIndex = 2, songIds = listOf(10), next = false))
        assertEquals(listOf(1L, 2L, GAP, GAP, 10L), insertIntoSavedQueue(listOf(1, 2), savedIndex = 3, songIds = listOf(10), next = true))
    }

    @Test
    fun `restored after inserting behind a deleted last title, the new song starts from its beginning`() {
        val queue = insertIntoSavedQueue(listOf(1, 2), savedIndex = 2, songIds = listOf(10), next = true)
        assertEquals(RestoredStart(index = 2, sameSong = false), restoredStart(queue, setOf(1L, 2L, 10L), savedIndex = 2))
    }

    private companion object {
        const val GAP = PlaybackStateRepository.QUEUE_GAP
    }
}
