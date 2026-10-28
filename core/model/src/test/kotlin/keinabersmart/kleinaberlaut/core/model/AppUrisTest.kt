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
    fun `artist image uri round trips and is not an album cover`() {
        assertEquals(5L, AppUris.parseArtistImageId(AppUris.artistImage(5)))
        assertNull(AppUris.parseAlbumCoverId(AppUris.artistImage(5)))
        assertNull(AppUris.parseArtistImageId(AppUris.albumCover(5)))
    }

    @Test
    fun `foreign uris are rejected`() {
        assertNull(AppUris.parseSongId("content://media/external/audio/media/42"))
        assertNull(AppUris.parseSongId(AppUris.albumCover(42)))
        assertNull(AppUris.parseAlbumCoverId("kal://cover/album/abc"))
    }
}
