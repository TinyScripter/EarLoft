package de.kleinabersmart.earloft.ui.home

import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpNextTest {

    private fun entry(id: Long) = QueueEntry(songId = id, title = "Titel $id", artist = "Band", albumId = null)

    private val queue = List(30) { entry(it.toLong()) }

    @Test
    fun `starts with the current song and keeps the queue positions`() {
        val rows = upNext(PlayerState(current = queue[5], queue = queue, currentIndex = 5))

        assertEquals(5, rows.first().index)
        assertEquals(queue[5], rows.first().entry)
        assertEquals((5 until 25).toList(), rows.map { it.index })
    }

    @Test
    fun `the same song twice gets two rows`() {
        val twice = listOf(entry(1), entry(1))
        val rows = upNext(PlayerState(current = twice[0], queue = twice, currentIndex = 0))

        assertEquals(listOf(0, 1), rows.map { it.index })
    }

    @Test
    fun `nothing while no music plays`() {
        assertTrue(upNext(PlayerState()).isEmpty())
        assertTrue(upNext(PlayerState(spoken = SpokenEntry(1, 0, "Buch", "Autor"), queue = queue, currentIndex = 0)).isEmpty())
    }
}
