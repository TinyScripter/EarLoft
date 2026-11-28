package de.kleinabersmart.earloft.core.playback

import de.kleinabersmart.earloft.core.model.SpokenMode
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
    fun `a tap on the second occurrence of a song in a playlist starts there`() {
        val playlist = listOf(1L, 42L, 3L, 42L)
        val second = MediaIds.inContext(MediaIds.playlist(9), 42, position = 3)
        assertEquals(42L, MediaIds.songId(second))
        assertEquals(MediaIds.playlist(9), MediaIds.contextOf(second))

        assertEquals(3, startIndexIn(playlist, second))
        assertEquals(1, startIndexIn(playlist, MediaIds.inContext(MediaIds.playlist(9), 42, position = 1)))
        // The playlist changed since Auto listed it: the song where it is now; gone, nothing.
        assertEquals(1, startIndexIn(listOf(1L, 42L), second))
        assertEquals(-1, startIndexIn(listOf(1L, 3L), second))
        // Older ids without a position, and a search query with a hash in it.
        assertEquals(1, startIndexIn(playlist, MediaIds.inContext(MediaIds.playlist(9), 42)))
        assertEquals(7L, MediaIds.songId(MediaIds.inContext(MediaIds.search("C#/D"), 7, position = 0)))
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

    @Test
    fun `spoken, feed, author and series ids round trip`() {
        assertEquals(12L, MediaIds.spokenId(MediaIds.spoken(12)))
        assertNull(MediaIds.spokenId(BookIds.mediaId(12, 0)))
        assertEquals("local:Podcasts/Show", MediaIds.feedId(MediaIds.feed("local:Podcasts/Show")))
        assertEquals("AC/DC", MediaIds.authorName(MediaIds.author("AC/DC")))
        assertEquals("Carl Mørck", MediaIds.seriesName(MediaIds.series("Carl Mørck")))
        assertNull(MediaIds.authorName(MediaIds.series("x")))
        assertNull(MediaIds.songId(MediaIds.feed("local:Podcasts/Show")))
    }

    @Test
    fun `every id knows its area`() {
        assertEquals(SpokenMode.MUSIC, MediaIds.areaOf(MediaIds.inContext(MediaIds.album(3), 42)))
        assertEquals(SpokenMode.MUSIC, MediaIds.areaOf(MediaIds.inContext(MediaIds.search("x"), 1)))
        assertEquals(SpokenMode.MUSIC, MediaIds.areaOf(MediaIds.FAVORITES))
        assertEquals(SpokenMode.MUSIC, MediaIds.areaOf(MediaIds.FAVORITE_ALBUMS))
        assertEquals(SpokenMode.MUSIC, MediaIds.areaOf(MediaIds.FAVORITE_ARTISTS))
        assertEquals(SpokenMode.PODCASTS, MediaIds.areaOf(MediaIds.feed("abc")))
        assertEquals(SpokenMode.PODCASTS, MediaIds.areaOf(MediaIds.EPISODES_NEW))
        assertEquals(SpokenMode.BOOKS, MediaIds.areaOf(MediaIds.author("Fitzek")))
        assertEquals(SpokenMode.BOOKS, MediaIds.areaOf(MediaIds.BOOKS_ALL))
        assertNull(MediaIds.areaOf(MediaIds.ROOT))
        assertNull(MediaIds.areaOf(MediaIds.HOME))
        assertNull(MediaIds.areaOf(MediaIds.spoken(1)))
    }
}
