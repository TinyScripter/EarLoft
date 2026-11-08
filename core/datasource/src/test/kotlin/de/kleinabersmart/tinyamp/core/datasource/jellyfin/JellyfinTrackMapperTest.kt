package de.kleinabersmart.tinyamp.core.datasource.jellyfin

import de.kleinabersmart.tinyamp.core.datasource.RawArtist
import de.kleinabersmart.tinyamp.core.datasource.local.FallbackNames
import de.kleinabersmart.tinyamp.core.model.LyricLine
import de.kleinabersmart.tinyamp.core.model.Lyrics
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfItem
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfLyricLine
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfLyricMetadata
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfLyrics
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfMediaSource
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfNameId
import de.kleinabersmart.tinyamp.core.network.jellyfin.JfUserData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JellyfinTrackMapperTest {

    private val names = FallbackNames(
        unknownTitle = "Unbekannter Titel",
        unknownArtist = "Unbekannter Künstler",
        unknownAlbum = "Unbekanntes Album",
        variousArtists = "Verschiedene Künstler",
    )

    /** Wie ein Song vom Testserver (Jellyfin 12.4), gekürzt. */
    private val song = JfItem(
        id = "s1",
        name = "20th Century Fox Fanfare",
        type = "Audio",
        album = "Bohemian Rhapsody",
        albumId = "al1",
        albumArtist = "Queen",
        albumArtists = listOf(JfNameId("Queen", "ar1")),
        artistItems = listOf(JfNameId("Queen", "ar1")),
        artists = listOf("Queen"),
        indexNumber = 1,
        parentIndexNumber = 1,
        runTimeTicks = 252_075_000,
        productionYear = 2018,
        genres = listOf("Soundtrack"),
        providerIds = mapOf(
            "MusicBrainzRecording" to "8b70a6a6-b086-47cd-837a-0211c70ba818",
            "MusicBrainzTrack" to "7a43b22f-f867-4b4e-9d0d-385745beec4e",
            "MusicBrainzAlbum" to "dbb05339-b2c7-46e7-9f1a-9e67bc97d0d1",
            "MusicBrainzArtist" to "0383dadf-2a4e-4d10-a46a-e9e041da8eb3",
            "MusicBrainzAlbumArtist" to "0383dadf-2a4e-4d10-a46a-e9e041da8eb3",
        ),
        normalizationGain = -9.2f,
        albumNormalizationGain = -8.6f,
        container = "ogg",
        mediaSources = listOf(JfMediaSource(size = 497_647, bitrate = 157_936, container = "ogg")),
        dateCreated = "2026-10-25T19:12:26.0000000Z",
        userData = JfUserData(isFavorite = false),
        imageTags = mapOf("Primary" to "t1"),
        albumPrimaryImageTag = "t2",
    )

    @Test
    fun `maps metadata, recording and release ids, gains and file details`() {
        val track = JellyfinTrackMapper.map(song, names)

        assertEquals("s1", track.remoteId)
        assertEquals("Queen", track.artistDisplay)
        assertEquals(listOf(RawArtist("Queen", "0383dadf-2a4e-4d10-a46a-e9e041da8eb3")), track.artists)
        assertEquals(listOf(RawArtist("Queen", "0383dadf-2a4e-4d10-a46a-e9e041da8eb3")), track.albumArtists)
        assertEquals(25_207L, track.durationMs)
        assertEquals("8b70a6a6-b086-47cd-837a-0211c70ba818", track.mbidTrack)
        assertEquals("dbb05339-b2c7-46e7-9f1a-9e67bc97d0d1", track.mbidAlbum)
        assertEquals(-9.2f, track.rgTrackGainDb)
        assertEquals(-8.6f, track.rgAlbumGainDb)
        assertNull(track.rgTrackPeak)
        assertEquals("al1:t2", track.coverRemoteId)
        assertEquals("audio/ogg", track.mimeType)
        assertEquals(157_936, track.bitRate)
        assertEquals(497_647L, track.sizeBytes)
        assertEquals(1_792_955_546_000L, track.dateAdded)
        assertNull(track.starredAt)
        assertFalse(track.isCompilation)
    }

    @Test
    fun `several artists share no mbid, samplers become various artists`() {
        val track = JellyfinTrackMapper.map(
            song.copy(
                artistItems = listOf(JfNameId("A"), JfNameId("B")),
                albumArtist = "Various Artists",
                albumArtists = listOf(JfNameId("Various Artists")),
            ),
            names,
        )

        assertEquals("A & B", track.artistDisplay)
        assertEquals(listOf(RawArtist("A"), RawArtist("B")), track.artists)
        assertTrue(track.isCompilation)
        assertEquals("Verschiedene Künstler", track.albumArtistDisplay)
    }

    @Test
    fun `favorite is known without time and does not change the change key`() {
        val plain = JellyfinTrackMapper.map(song, names)
        val starred = JellyfinTrackMapper.map(song.copy(userData = JfUserData(isFavorite = true)), names)
        val retagged = JellyfinTrackMapper.map(song.copy(indexNumber = 2), names)

        assertEquals(0L, starred.starredAt)
        assertEquals(plain.lastModified, starred.lastModified)
        assertNotEquals(plain.lastModified, retagged.lastModified)
    }

    @Test
    fun `cover falls back to the song image, missing values get fallbacks`() {
        val track = JellyfinTrackMapper.map(
            JfItem(id = "s2", type = "Audio", imageTags = mapOf("Primary" to "x"), albumId = "al2"),
            names,
        )

        assertEquals("s2:x", track.coverRemoteId)
        assertEquals("Unbekannter Titel", track.title)
        assertEquals("Unbekannter Künstler", track.artistDisplay)
        assertEquals("Unbekanntes Album", track.albumTitle)
        assertEquals(1, track.discNumber)
        assertNull(JellyfinTrackMapper.map(JfItem(id = "s3", type = "Audio"), names).coverRemoteId)
    }

    @Test
    fun `mime type follows the container list`() {
        assertEquals("audio/mp4", JellyfinTrackMapper.mimeTypeFor("mov,mp4,m4a,3gp,3g2,mj2"))
        assertEquals("audio/mpeg", JellyfinTrackMapper.mimeTypeFor("mp3"))
        assertEquals("audio/flac", JellyfinTrackMapper.mimeTypeFor("FLAC"))
        assertNull(JellyfinTrackMapper.mimeTypeFor("xyz"))
        assertNull(JellyfinTrackMapper.mimeTypeFor(null))
    }

    @Test
    fun `lyrics are synced only with timestamps on every line`() {
        val synced = JfLyrics(
            JfLyricMetadata(isSynced = true),
            listOf(JfLyricLine("Zwei", 20_000_000), JfLyricLine("Eins", 10_000_000)),
        )
        assertEquals(Lyrics.Synced(listOf(LyricLine(1_000, "Eins"), LyricLine(2_000, "Zwei"))), JellyfinTrackMapper.lyrics(synced))

        val plain = JfLyrics(JfLyricMetadata(), listOf(JfLyricLine("Habe die Ehre"), JfLyricLine(""), JfLyricLine("Ja nee")))
        assertEquals(Lyrics.Plain("Habe die Ehre\n\nJa nee"), JellyfinTrackMapper.lyrics(plain))
        assertEquals(Lyrics.None, JellyfinTrackMapper.lyrics(JfLyrics()))
    }

    @Test
    fun `playlist change marker follows name and entries`() {
        val base = JellyfinTrackMapper.playlistInfo("p1", "Sommer", listOf("a", "b"))

        assertEquals(base, JellyfinTrackMapper.playlistInfo("p1", "Sommer", listOf("a", "b")))
        assertNotEquals(base.changedAt, JellyfinTrackMapper.playlistInfo("p1", "Sommer", listOf("b", "a")).changedAt)
        assertNotEquals(base.changedAt, JellyfinTrackMapper.playlistInfo("p1", "Winter", listOf("a", "b")).changedAt)
    }
}
