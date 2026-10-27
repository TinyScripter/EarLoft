package keinabersmart.kleinaberlaut.core.datasource.subsonic

import keinabersmart.kleinaberlaut.core.datasource.local.FallbackNames
import keinabersmart.kleinaberlaut.core.model.LyricLine
import keinabersmart.kleinaberlaut.core.model.Lyrics
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.AlbumDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.ArtistRefDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.ReplayGainDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.LyricLineDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.SongDto
import keinabersmart.kleinaberlaut.core.network.subsonic.dto.StructuredLyricsDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubsonicTrackMapperTest {

    private val names = FallbackNames(
        unknownTitle = "Unbekannter Titel",
        unknownArtist = "Unbekannter Künstler",
        unknownAlbum = "Unbekanntes Album",
        variousArtists = "Verschiedene Künstler",
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
        assertNull("Leere MBIDs zählen nicht", track.mbidTrack)
        assertEquals("al-al1_abc", track.coverRemoteId)
        assertEquals(-6.5f, track.rgTrackGainDb)
        assertEquals(1_793_055_915_941L, track.dateAdded)
    }

    @Test
    fun `compilations use the local name for various artists`() {
        val sampler = song.copy(displayAlbumArtist = "Various Artists", albumArtists = listOf(ArtistRefDto(name = "Various Artists")))

        val track = SubsonicTrackMapper.map(sampler, album.copy(isCompilation = false), names)

        assertTrue(track.isCompilation)
        assertEquals("Verschiedene Künstler", track.albumArtistDisplay)
        assertEquals(listOf("Verschiedene Künstler"), track.albumArtists.map { it.name })
    }

    @Test
    fun `missing tags fall back to placeholders`() {
        val track = SubsonicTrackMapper.map(SongDto(id = "x"), null, names)

        assertEquals("Unbekannter Titel", track.title)
        assertEquals("Unbekannter Künstler", track.artistDisplay)
        assertEquals("Unbekanntes Album", track.albumTitle)
        assertEquals(track.artistDisplay, track.albumArtistDisplay)
    }

    @Test
    fun `change key is stable and reacts to retagging`() {
        val first = SubsonicTrackMapper.map(song, album, names).lastModified
        assertEquals(first, SubsonicTrackMapper.map(song.copy(), album, names).lastModified)
        assertNotEquals(first, SubsonicTrackMapper.map(song.copy(title = "Mondlicht (Remaster)"), album, names).lastModified)
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
                StructuredLyricsDto(synced = false, line = listOf(LyricLineDto(value = "statisch"))),
                StructuredLyricsDto(synced = true, line = listOf(LyricLineDto(5_000, "zwei"), LyricLineDto(0, "eins"))),
            ),
        )

        assertEquals(Lyrics.Synced(listOf(LyricLine(0, "eins"), LyricLine(5_000, "zwei"))), lyrics)
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
