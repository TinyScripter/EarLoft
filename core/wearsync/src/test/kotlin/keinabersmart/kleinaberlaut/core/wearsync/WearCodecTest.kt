package keinabersmart.kleinaberlaut.core.wearsync

import keinabersmart.kleinaberlaut.core.model.ReplayGainMode
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import org.junit.Assert.assertEquals
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
        album = "Mondlicht",
        albumArtist = "Nachtfalter",
        durationMs = 180_000,
        replayGain = ReplayGainDto(trackGainDb = -4.5f, albumPeak = 0.98f),
        serverSources = listOf(WearServerSourceDto(uid = "nd:1", sourceId = "nd", remoteId = "1", sizeBytes = 30_000_000)),
        phoneFile = PhoneFileDto(uid = "local:7", sizeBytes = 30_000_000, mimeType = "audio/flac"),
    )

    private val manifest = WearManifest(
        selections = listOf(
            WearSelectionDto("playlist:3", WearSelectionKind.PLAYLIST, "Laufen", songKeys = listOf("nd:1", "nd:1")),
            WearSelectionDto("favorites", WearSelectionKind.FAVORITES, "Favoriten", songKeys = listOf("nd:1")),
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

        assertTrue("gzip $compressed von $raw Bytes", compressed * 5 < raw)
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
        val json = """{"sourceId":"nd","password":"geheim","expiresAt":123}"""

        assertEquals(WearCredentialsDto("nd", "geheim"), WearCodec.decode<WearCredentialsDto>(json.encodeToByteArray()))
    }

    @Test
    fun `missing optional fields fall back to their defaults`() {
        val settings = WearCodec.decode<WearSettingsDto>("{}".encodeToByteArray())

        assertEquals(WearQuality.OPUS_128, settings.quality)
        assertEquals(WearSettingsDto.DEFAULT_MIN_FREE_BYTES, settings.minFreeBytes)
        assertTrue(settings.requireCharging)
        assertEquals(ReplayGainMode.AUTO, settings.replayGainMode)
        assertNull(settings.storageLimitBytes)
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
}
