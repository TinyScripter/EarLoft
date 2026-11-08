package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearEpisodeTest {

    @Test
    fun `Kennung im Datei-Kanal uebersteht beliebige guids`() {
        val guid = "https://example.org/folge/12:a b?x=1&y=ä"
        val uid = WearEpisodeFile.uid("local:3f2a", guid)
        assertEquals("local:3f2a" to guid, WearEpisodeFile.parse(uid))
        // Kein Schrägstrich im Pfad des Kanals.
        assertEquals(false, uid.contains('/'))
    }

    @Test
    fun `Song-Kennungen sind keine Folgen`() {
        assertNull(WearEpisodeFile.parse("srv1:1234"))
        assertNull(WearEpisodeFile.parse("episode:nur-ein-teil"))
    }

    @Test
    fun `Folgen-Manifest uebersteht gzip`() {
        val manifest = WearPodcastManifest(
            feeds = listOf(WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast")),
            episodes = listOf(
                WearEpisodeDto(
                    feedId = "f1",
                    guid = "g1",
                    title = "Folge 1",
                    durationMs = 3_600_000,
                    audioUrl = "https://example.org/1.mp3",
                    progress = WearEpisodeProgressDto(positionMs = 60_000, durationMs = 3_600_000, updatedAt = 5),
                ),
            ),
        )
        assertEquals(manifest, WearCodec.decodeCompressed<WearPodcastManifest>(WearCodec.encodeCompressed(manifest)))
    }

    @Test
    fun `Einstellungen einer alten App mit Folgenzahl lassen sich lesen`() {
        val decoded = WearCodec.decode<WearSettingsDto>("""{"requireCharging":false,"podcastEpisodes":3}""".encodeToByteArray())
        assertEquals(false, decoded.requireCharging)
    }
}
