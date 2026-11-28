package de.kleinabersmart.earloft.core.wearsync

import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.ReplayGainMode
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WearCodecTest {

    private val song = WearSongDto(
        key = "nd:1",
        title = "東京事変",
        artists = listOf("Björk"),
        artistDisplay = "Björk",
        album = "Moonlight",
        albumArtist = "Night Moth",
        durationMs = 180_000,
        replayGain = ReplayGainDto(trackGainDb = -4.5f, albumPeak = 0.98f),
        serverSources = listOf(WearServerSourceDto(uid = "nd:1", sourceId = "nd", remoteId = "1", sizeBytes = 30_000_000)),
        phoneFile = PhoneFileDto(uid = "local:7", sizeBytes = 30_000_000, mimeType = "audio/flac"),
    )

    private val manifest = WearManifest(
        selections = listOf(
            WearSelectionDto("playlist:3", WearSelectionKind.PLAYLIST, "Running", songKeys = listOf("nd:1", "nd:1")),
            WearSelectionDto("favorites", WearSelectionKind.FAVORITES, "Favorites", songKeys = listOf("nd:1")),
        ),
        songs = listOf(song),
    )

    @Test
    fun `manifest survives gzip round trip including non-latin text and duplicates`() {
        val decoded = WearCodec.decodeManifest(WearCodec.encodeManifest(manifest))

        assertEquals(manifest, decoded)
        assertEquals(listOf("nd:1", "nd:1"), decoded.selections.first().songKeys)
    }

    @Test
    fun `large manifests are compressed well below the data item limit`() {
        val songs = List(5_000) { song.copy(key = "nd:$it", serverSources = listOf(song.serverSources.first().copy(uid = "nd:$it", remoteId = "$it"))) }
        val big = manifest.copy(songs = songs)

        val raw = WearCodec.encode(big).size
        val compressed = WearCodec.encodeManifest(big).size

        assertTrue("gzip $compressed of $raw bytes", compressed * 5 < raw)
    }

    @Test
    fun `hash is stable and changes with the content`() {
        assertEquals(WearCodec.manifestHash(manifest), WearCodec.manifestHash(manifest.copy()))
        assertNotEquals(
            WearCodec.manifestHash(manifest),
            WearCodec.manifestHash(manifest.copy(selections = manifest.selections.take(1))),
        )
    }

    @Test
    fun `unknown fields from a newer app version are ignored`() {
        val json = """{"sourceId":"nd","password":"secret","expiresAt":123}"""

        assertEquals(WearCredentialsDto("nd", "secret"), WearCodec.decode<WearCredentialsDto>(json.encodeToByteArray()))
    }

    @Test
    fun `credentials carry the device only when there is one`() {
        assertEquals("""{"sourceId":"jf","password":"u:t"}""", WearCodec.encode(WearCredentialsDto("jf", "u:t")).decodeToString())
        val withDevice = WearCredentialsDto("jf", "u:t", deviceId = "earloft-1234")
        assertEquals(withDevice, WearCodec.decode<WearCredentialsDto>(WearCodec.encode(withDevice)))
    }

    @Test
    fun `servers of an older phone are subsonic servers`() {
        val json = """{"servers":[{"sourceId":"nd","name":"Navidrome","baseUrl":"https://nd.example","username":"anna"}]}"""

        val servers = WearCodec.decode<WearServersDto>(json.encodeToByteArray())

        assertEquals("SUBSONIC", servers.servers.single().type)
        assertTrue(servers.otherServers.isEmpty())
    }

    @Test
    fun `other servers keep their type`() {
        val jellyfin = WearServerDto("jf", "JELLYFIN", "Jellyfin", "https://jf.example", "anna")
        val servers = WearServersDto(otherServers = listOf(jellyfin))

        assertEquals(servers, WearCodec.decode<WearServersDto>(WearCodec.encode(servers)))
    }

    @Test
    fun `missing optional fields fall back to their defaults`() {
        val settings = WearCodec.decode<WearSettingsDto>("{}".encodeToByteArray())

        assertEquals(WearQuality.OPUS_128, settings.quality)
        assertEquals(WearSettingsDto.DEFAULT_MIN_FREE_BYTES, settings.minFreeBytes)
        assertTrue(settings.requireCharging)
        assertEquals(ReplayGainMode.AUTO, settings.replayGainMode)
        assertNull(settings.storageLimitBytes)
        assertEquals(SpokenMode.entries, settings.areas)
        assertEquals(10, settings.bookSeekBackSeconds)
        assertEquals(30, settings.podcastSeekForwardSeconds)
    }

    @Test
    fun `areas and seek steps survive the round trip`() {
        val settings = WearSettingsDto(areas = listOf(SpokenMode.MUSIC, SpokenMode.BOOKS), bookSeekBackSeconds = 15, podcastSeekForwardSeconds = 60)

        assertEquals(settings, WearCodec.decode<WearSettingsDto>(WearCodec.encode(settings)))
    }

    @Test
    fun `seek steps alone do not count as a download change`() {
        val settings = WearSettingsDto(requireCharging = false)

        assertEquals(settings.withoutSeek(), settings.copy(bookSeekBackSeconds = 5, podcastSeekForwardSeconds = 45).withoutSeek())
        assertTrue(settings.withoutSeek() != settings.copy(requireCharging = true).withoutSeek())
    }

    @Test
    fun `only quality and the charger rule restart a running download`() {
        val settings = WearSettingsDto(requireCharging = false)

        assertTrue(settings.copy(quality = WearQuality.ORIGINAL).restartsDownloadsAfter(settings))
        assertTrue(settings.copy(requireCharging = true).restartsDownloadsAfter(settings))
        listOf(
            settings.copy(preAmpDb = 3f),
            settings.copy(replayGainMode = ReplayGainMode.OFF),
            settings.copy(storageLimitBytes = 2_000_000_000L),
            settings.copy(minFreeBytes = 500_000_000L),
            settings.copy(areas = listOf(SpokenMode.MUSIC)),
        ).forEach { changed -> assertFalse("$changed", changed.restartsDownloadsAfter(settings)) }
    }

    @Test
    fun `enums are encoded by name and nulls are left out`() {
        val json = WearCodec.encode(WearSelectRequest(WearSelectionKind.FAVORITES, selected = true)).decodeToString()

        assertEquals("""{"kind":"FAVORITES","selected":true}""", json)
    }

    @Test
    fun `selection keys match between phone and watch`() {
        assertEquals("album:12", WearSelectionKind.ALBUM.key(12))
        assertEquals("playlist:3", WearSelectionKind.PLAYLIST.key(3))
        assertEquals("favorites", WearSelectionKind.FAVORITES.key(null))
    }

    @Test
    fun `an unknown clear scope from a newer watch is no request to remove everything`() {
        // review5 P72: coerced to the default, it became ALL.
        assertEquals(null, WearCodec.decode<WearClearRequest>("""{"scope":"AUDIOBOOK_SHELVES"}""".encodeToByteArray()).scope)
        assertEquals(WearClearScope.MUSIC, WearCodec.decode<WearClearRequest>(WearCodec.encode(WearClearRequest(WearClearScope.MUSIC))).scope)
        assertEquals(WearClearScope.ALL, WearCodec.decode<WearClearRequest>(WearCodec.encode(WearClearRequest(WearClearScope.ALL))).scope)
    }
}
