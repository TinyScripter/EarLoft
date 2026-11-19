package de.kleinabersmart.earloft.core.wearsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearEpisodeTest {

    @Test
    fun `id in the file channel survives arbitrary guids`() {
        val guid = "https://example.org/episode/12:a b?x=1&y=ä"
        val uid = WearEpisodeFile.uid("local:3f2a", guid)
        assertEquals("local:3f2a" to guid, WearEpisodeFile.parse(uid))
        // No slash in the channel's path.
        assertEquals(false, uid.contains('/'))
    }

    @Test
    fun `song ids are not episodes`() {
        assertNull(WearEpisodeFile.parse("srv1:1234"))
        assertNull(WearEpisodeFile.parse("episode:only-one-part"))
    }

    @Test
    fun `episode manifest survives gzip`() {
        val manifest = WearPodcastManifest(
            feeds = listOf(WearPodcastFeedDto("f1", "https://example.org/feed", "Podcast")),
            episodes = listOf(
                WearEpisodeDto(
                    feedId = "f1",
                    guid = "g1",
                    title = "Episode 1",
                    durationMs = 3_600_000,
                    audioUrl = "https://example.org/1.mp3",
                    progress = WearEpisodeProgressDto(positionMs = 60_000, durationMs = 3_600_000, updatedAt = 5),
                ),
            ),
        )
        assertEquals(manifest, WearCodec.decodeCompressed<WearPodcastManifest>(WearCodec.encodeCompressed(manifest)))
    }

    @Test
    fun `settings of an old app with episode count can be read`() {
        val decoded = WearCodec.decode<WearSettingsDto>("""{"requireCharging":false,"podcastEpisodes":3}""".encodeToByteArray())
        assertEquals(false, decoded.requireCharging)
    }
}
