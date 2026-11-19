package de.kleinabersmart.earloft.core.datasource.subsonic

import de.kleinabersmart.earloft.core.datasource.local.FallbackNames
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics
import de.kleinabersmart.earloft.core.network.subsonic.dto.AlbumDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.ArtistRefDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.ItemGenreDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.ReplayGainDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.LyricLineDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.SongDto
import de.kleinabersmart.earloft.core.network.subsonic.dto.StructuredLyricsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicTrackMapperTest {

    private val names = FallbackNames(
        unknownTitle = "Unknown title",
        unknownArtist = "Unknown artist",
        unknownAlbum = "Unknown album",
        variousArtists = "Various artists",
    )

    private val album = AlbumDto(
        id = "al1",
        name = "Mondlicht",
        artist = "Nachtfalter",
        coverArt = "al-al1_abc",
        year = 2022,
        musicBrainzId = "22222222-2222-4222-8222-222222222222",
    )

    private val song = SongDto(
        id = "s1",
        title = "Mondlicht",
        album = "Mondlicht",
        artist = "Nachtfalter",
        albumId = "al1",
        track = 1,
        coverArt = "mf-s1",
        contentType = "audio/flac",
        duration = 40,
        bitRate = 404,
        created = "2026-10-27T00:05:15.941029244+01:00",
        musicBrainzId = "",
        replayGain = ReplayGainDto(trackGain = -6.5f, trackPeak = 0.98f),
        artists = listOf(ArtistRefDto(name = "Nachtfalter")),
        displayArtist = "Nachtfalter",
        albumArtists = listOf(ArtistRefDto(name = "Nachtfalter")),
        displayAlbumArtist = "Nachtfalter",
    )

    @Test
    fun `units are converted and album metadata is taken from the album`() {
        val track = SubsonicTrackMapper.map(song, album, names)

        assertEquals(40_000L, track.durationMs)
        assertEquals(404_000, track.bitRate)
        assertEquals(1, track.discNumber)
        assertEquals("22222222-2222-4222-8222-222222222222", track.mbidAlbum)
        assertNull("Empty MBIDs don't count", track.mbidTrack)
        assertEquals("al-al1_abc", track.coverRemoteId)
        assertEquals(-6.5f, track.rgTrackGainDb)
        assertEquals(1_793_055_915_941L, track.dateAdded)
    }

    @Test
    fun `compilations use the local name for various artists`() {
        val sampler = song.copy(displayAlbumArtist = "Various Artists", albumArtists = listOf(ArtistRefDto(name = "Various Artists")))

        val track = SubsonicTrackMapper.map(sampler, album.copy(isCompilation = false), names)

        assertTrue(track.isCompilation)
        assertEquals("Various artists", track.albumArtistDisplay)
        assertEquals(listOf("Various artists"), track.albumArtists.map { it.name })
    }

    @Test
    fun `album and artist ids are kept for favorites`() {
        val openSubsonic = song.copy(
            artists = listOf(ArtistRefDto(id = "ar1", name = "Nachtfalter")),
            albumArtists = listOf(ArtistRefDto(id = "ar1", name = "Nachtfalter")),
        )
        val track = SubsonicTrackMapper.map(openSubsonic, album, names)
        assertEquals("al1", track.albumRemoteId)
        assertEquals(listOf("ar1"), track.artists.map { it.remoteId })
        assertEquals(listOf("ar1"), track.albumArtists.map { it.remoteId })

        // Classic Subsonic: only artistId on the song and on the album.
        val classic = SubsonicTrackMapper.map(
            song.copy(artists = emptyList(), albumArtists = emptyList(), displayAlbumArtist = null, artistId = "ar2"),
            album.copy(artistId = "ar3"),
            names,
        )
        assertEquals(listOf("ar2"), classic.artists.map { it.remoteId })
        assertEquals(listOf("ar3"), classic.albumArtists.map { it.remoteId })
    }

    @Test
    fun `missing tags fall back to placeholders`() {
        val track = SubsonicTrackMapper.map(SongDto(id = "x"), null, names)

        assertEquals("Unknown title", track.title)
        assertEquals("Unknown artist", track.artistDisplay)
        assertEquals("Unknown album", track.albumTitle)
        assertEquals(track.artistDisplay, track.albumArtistDisplay)
    }

    @Test
    fun `change key is stable and reacts to retagging`() {
        val first = SubsonicTrackMapper.map(song, album, names).lastModified
        assertEquals(first, SubsonicTrackMapper.map(song.copy(), album, names).lastModified)
        assertNotEquals(first, SubsonicTrackMapper.map(song.copy(title = "Mondlicht (Remaster)"), album, names).lastModified)
        // An additional genre in the OpenSubsonic list is a change.
        assertNotEquals(first, SubsonicTrackMapper.map(song.copy(genres = listOf(ItemGenreDto("Rap"))), album, names).lastModified)
    }

    @Test
    fun `all opensubsonic genres are kept, classic servers name one`() {
        val both = SubsonicTrackMapper.map(SongDto(id = "x", genre = "Hip-Hop", genres = listOf(ItemGenreDto("Hip-Hop"), ItemGenreDto("Rap"))), null, names)
        assertEquals("Hip-Hop", both.genre)
        assertEquals(listOf("Hip-Hop", "Rap"), both.allGenres)

        assertEquals(listOf("Jazz"), SubsonicTrackMapper.map(SongDto(id = "x", genre = "Jazz"), null, names).allGenres)
        val onlyList = SubsonicTrackMapper.map(SongDto(id = "x", genres = listOf(ItemGenreDto(" "), ItemGenreDto("Rap"))), null, names)
        assertEquals("Rap", onlyList.genre)
        assertEquals(listOf("Rap"), onlyList.allGenres)
    }

    @Test
    fun `podcasts and folders are skipped`() {
        assertTrue(SubsonicTrackMapper.isMusic(song))
        assertFalse(SubsonicTrackMapper.isMusic(song.copy(type = "podcast")))
        assertFalse(SubsonicTrackMapper.isMusic(song.copy(isDir = true)))
    }

    @Test
    fun `star time is parsed but does not count as a change`() {
        val unstarred = SubsonicTrackMapper.map(song, album, names)
        val starred = SubsonicTrackMapper.map(song.copy(starred = "2026-10-27T07:56:33.043757485+01:00"), album, names)

        assertNull(unstarred.starredAt)
        assertEquals(1_793_084_193_043L, starred.starredAt)
        assertEquals(unstarred.lastModified, starred.lastModified)
    }

    @Test
    fun `synced lyrics are preferred and sorted`() {
        val lyrics = SubsonicTrackMapper.lyrics(
            listOf(
                StructuredLyricsDto(synced = false, line = listOf(LyricLineDto(value = "static"))),
                StructuredLyricsDto(synced = true, line = listOf(LyricLineDto(5_000, "two"), LyricLineDto(0, "one"))),
            ),
        )

        assertEquals(Lyrics.Synced(listOf(LyricLine(0, "one"), LyricLine(5_000, "two"))), lyrics)
    }

    @Test
    fun `offset moves synced lines earlier or later`() {
        val lines = listOf(LyricLineDto(1_000, "one"), LyricLineDto(5_000, "two"))

        assertEquals(
            Lyrics.Synced(listOf(LyricLine(0, "one"), LyricLine(3_500, "two"))),
            SubsonicTrackMapper.lyrics(listOf(StructuredLyricsDto(synced = true, offset = 1_500, line = lines))),
        )
        assertEquals(
            Lyrics.Synced(listOf(LyricLine(1_250, "one"), LyricLine(5_250, "two"))),
            SubsonicTrackMapper.lyrics(listOf(StructuredLyricsDto(synced = true, offset = -250, line = lines))),
        )
    }

    @Test
    fun `plain lyrics are joined and empty ones are none`() {
        val plain = StructuredLyricsDto(synced = false, line = listOf(LyricLineDto(value = "a"), LyricLineDto(value = "b")))

        assertEquals(Lyrics.Plain("a\nb"), SubsonicTrackMapper.lyrics(listOf(plain)))
        assertEquals(Lyrics.None, SubsonicTrackMapper.lyrics(listOf(plain.copy(line = listOf(LyricLineDto(value = " "))))))
        assertEquals(Lyrics.None, SubsonicTrackMapper.lyrics(emptyList()))
    }

    @Test
    fun `synced lyrics without timestamps fall back to text`() {
        val broken = StructuredLyricsDto(synced = true, line = listOf(LyricLineDto(value = "x"), LyricLineDto(value = "y")))

        assertEquals(Lyrics.Plain("x\ny"), SubsonicTrackMapper.lyrics(listOf(broken)))
    }
}
