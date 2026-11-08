package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaIdsTest {

    @Test
    fun `song and album ids round trip`() {
        assertEquals(42L, MediaIds.songId(MediaIds.song(42)))
        assertEquals(7L, MediaIds.albumId(MediaIds.album(7)))
    }

    @Test
    fun `ids of the other kind are not parsed`() {
        assertNull(MediaIds.songId(MediaIds.album(7)))
        assertNull(MediaIds.albumId(MediaIds.song(42)))
        assertNull(MediaIds.songId(MediaIds.ROOT))
        assertNull(MediaIds.albumId(MediaIds.ALBUMS))
    }

    @Test
    fun `song in a folder keeps its folder and its song id`() {
        val id = MediaIds.inContext(MediaIds.album(3), 42)
        assertEquals(42L, MediaIds.songId(id))
        assertEquals(MediaIds.album(3), MediaIds.contextOf(id))
        assertEquals(MediaIds.FAVORITES, MediaIds.contextOf(MediaIds.inContext(MediaIds.FAVORITES, 1)))
    }

    @Test
    fun `plain ids have no folder`() {
        assertNull(MediaIds.contextOf(MediaIds.song(42)))
        assertNull(MediaIds.contextOf(MediaIds.album(7)))
    }

    @Test
    fun `artist and playlist ids round trip`() {
        assertEquals(5L, MediaIds.artistId(MediaIds.artist(5)))
        assertEquals(9L, MediaIds.playlistId(MediaIds.playlist(9)))
        assertNull(MediaIds.artistId(MediaIds.playlist(9)))
    }

    @Test
    fun `search folder survives a slash in the query`() {
        val folder = MediaIds.search("AC/DC")
        val id = MediaIds.inContext(folder, 7)
        assertEquals("AC/DC", MediaIds.searchQuery(folder))
        assertEquals(folder, MediaIds.contextOf(id))
        assertEquals(7L, MediaIds.songId(id))
        assertNull(MediaIds.searchQuery(MediaIds.album(1)))
    }

    @Test
    fun `malformed ids are rejected`() {
        assertNull(MediaIds.songId("song:"))
        assertNull(MediaIds.songId("song:abc"))
        assertNull(MediaIds.songId("42"))
    }
}
