package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.model.Song
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedMusicQueueTest {

    private fun song(id: Long) = Song(
        id = id, title = "Title $id", artist = "Band", albumId = 7, albumTitle = "Album",
        durationMs = 1_000, discNumber = 1, trackNumber = id.toInt(), isFavorite = false,
    )

    @Test
    fun `entries carry title, artist and album of each song`() {
        val saved = savedMusicQueueOf(listOf(song(1), song(2)), savedIndex = 1)!!

        assertEquals(1, saved.index)
        assertEquals(QueueEntry(songId = 2, title = "Title 2", artist = "Band", albumId = 7, albumTitle = "Album"), saved.entries[1])
    }

    @Test
    fun `the saved index is limited to the songs like when resuming`() {
        assertEquals(1, savedMusicQueueOf(listOf(song(1), song(2)), savedIndex = 9)!!.index)
        assertEquals(0, savedMusicQueueOf(listOf(song(1)), savedIndex = -3)!!.index)
    }

    @Test
    fun `no saved queue without songs`() {
        assertNull(savedMusicQueueOf(emptyList(), savedIndex = 0))
    }
}
