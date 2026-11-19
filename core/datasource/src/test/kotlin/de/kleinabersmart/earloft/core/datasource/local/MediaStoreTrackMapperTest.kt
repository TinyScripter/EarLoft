package de.kleinabersmart.earloft.core.datasource.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreTrackMapperTest {

    private val names = FallbackNames(
        unknownTitle = "Unbekannter Titel",
        unknownArtist = "Unbekannter Künstler",
        unknownAlbum = "Unbekanntes Album",
        variousArtists = "Verschiedene Künstler",
    )

    @Test
    fun `splits disc and track number`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1, track = 2005)), names).single()

        assertEquals(2, track.discNumber)
        assertEquals(5, track.trackNumber)
    }

    @Test
    fun `tracks without disc use disc one`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1, track = 7)), names).single()

        assertEquals(1, track.discNumber)
        assertEquals(7, track.trackNumber)
    }

    @Test
    fun `tagged album artist wins`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1, artist = "A feat. B", albumArtist = "A")), names).single()

        assertEquals("A", track.albumArtistDisplay)
        assertEquals("A feat. B", track.artistDisplay)
    }

    @Test
    fun `untagged album with several artists becomes various artists`() {
        val tracks = MediaStoreTrackMapper.map(
            listOf(
                row(id = 1, artist = "Daft Punk", albumId = 10),
                row(id = 2, artist = "Air", albumId = 10),
                row(id = 3, artist = "Björk", albumId = 11),
            ),
            names,
        )

        assertEquals(listOf("Verschiedene Künstler", "Verschiedene Künstler", "Björk"), tracks.map { it.albumArtistDisplay })
        assertTrue(tracks[0].isCompilation)
    }

    @Test
    fun `unknown placeholders and zero year are normalised`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1, artist = "<unknown>", album = " ", year = 0)), names).single()

        assertEquals("Unbekannter Künstler", track.artistDisplay)
        assertEquals("Unbekanntes Album", track.albumTitle)
        assertNull(track.year)
    }

    @Test
    fun `timestamps are converted to milliseconds`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1)), names).single()

        // The millisecond digits carry the version of the tag reading.
        assertEquals(1_700_000_000_000 + MediaStoreTrackMapper.TAGS_VERSION, track.lastModified)
    }

    @Test
    fun `tags add mbids to track and its single artists`() {
        val track = MediaStoreTrackMapper.map(listOf(row(id = 1)), names).single()

        val tagged = MediaStoreTrackMapper.applyTags(
            track,
            EmbeddedTags(recordingMbid = "rec", releaseMbid = "rel", artistMbid = "art", albumArtistMbid = null),
        )

        assertEquals("rec", tagged.mbidTrack)
        assertEquals("rel", tagged.mbidAlbum)
        assertEquals("art", tagged.artists.single().mbid)
        assertNull(tagged.albumArtists.single().mbid)
    }

    private fun row(
        id: Long,
        artist: String? = "Sigur Rós",
        album: String? = "Takk...",
        albumId: Long = 1,
        albumArtist: String? = null,
        track: Int = 1,
        year: Int = 2005,
    ) = MediaStoreRow(
        id = id,
        title = "Titel $id",
        artist = artist,
        album = album,
        albumId = albumId,
        albumArtist = albumArtist,
        isCompilation = false,
        durationMs = 1000,
        track = track,
        year = year,
        genre = null,
        mimeType = "audio/flac",
        bitRate = null,
        sizeBytes = 1,
        dateAddedSec = 1_600_000_000,
        dateModifiedSec = 1_700_000_000,
    )
}
