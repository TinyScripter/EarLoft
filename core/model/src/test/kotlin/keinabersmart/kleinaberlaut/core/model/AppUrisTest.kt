package keinabersmart.kleinaberlaut.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppUrisTest {

    @Test
    fun `song uri round trips`() {
        assertEquals(42L, AppUris.parseSongId(AppUris.song(42)))
    }

    @Test
    fun `album cover uri round trips`() {
        assertEquals(7L, AppUris.parseAlbumCoverId(AppUris.albumCover(7)))
    }

    @Test
    fun `foreign uris are rejected`() {
        assertNull(AppUris.parseSongId("content://media/external/audio/media/42"))
        assertNull(AppUris.parseSongId(AppUris.albumCover(42)))
        assertNull(AppUris.parseAlbumCoverId("kal://cover/album/abc"))
    }
}
