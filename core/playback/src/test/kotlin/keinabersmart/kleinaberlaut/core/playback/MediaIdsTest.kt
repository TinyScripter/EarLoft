package keinabersmart.kleinaberlaut.core.playback

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
    fun `malformed ids are rejected`() {
        assertNull(MediaIds.songId("song:"))
        assertNull(MediaIds.songId("song:abc"))
        assertNull(MediaIds.songId("42"))
    }
}
