package de.kleinabersmart.earloft.ui.components

import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueuedAlbumTest {

    private fun entry(songId: Long, albumId: Long?) = QueueEntry(songId, "Song $songId", "Artist", albumId)

    private fun state(vararg entries: QueueEntry, current: Int = 0) =
        PlayerState(current = entries.getOrNull(current), queue = entries.toList(), currentIndex = current)

    @Test
    fun `a queue of only this album is the album`() {
        assertEquals(7L, state(entry(1, 7), entry(2, 7), entry(3, 7), current = 1).queuedAlbumId())
    }

    @Test
    fun `a playlist standing on a song of the album is not the album`() {
        assertNull(state(entry(1, 3), entry(2, 7), entry(3, 9), current = 1).queuedAlbumId())
    }

    @Test
    fun `the endless mix appended behind the album's last track keeps it the album`() {
        assertEquals(7L, state(entry(1, 7), entry(2, 7), entry(3, 3), entry(4, 9), current = 1).queuedAlbumId())
    }

    @Test
    fun `once the mix behind the album plays, the album is no longer loaded`() {
        assertNull(state(entry(1, 7), entry(2, 7), entry(3, 3), entry(4, 9), current = 2).queuedAlbumId())
    }

    @Test
    fun `a song of another album in between is no longer the album`() {
        assertNull(state(entry(1, 7), entry(2, 3), entry(3, 7), current = 0).queuedAlbumId())
    }

    @Test
    fun `songs without album, an empty queue and a loaded book count as no album`() {
        assertNull(state(entry(1, null), entry(2, null)).queuedAlbumId())
        assertNull(PlayerState().queuedAlbumId())
        assertNull(
            state(entry(1, 7)).copy(spoken = SpokenEntry(bookId = 4, trackIndex = 0, title = "Book", author = "Author"))
                .queuedAlbumId(),
        )
    }
}
