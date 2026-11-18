package de.kleinabersmart.earloft.core.playback

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
    fun `a saved index beyond the queue counts as its last song`() {
        assertEquals(listOf(1L, 2L, 10L), insertIntoSavedQueue(listOf(1, 2), savedIndex = 7, songIds = listOf(10), next = true))
    }
}
