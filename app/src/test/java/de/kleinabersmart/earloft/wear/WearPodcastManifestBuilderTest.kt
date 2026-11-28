package de.kleinabersmart.earloft.wear

import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeContent
import de.kleinabersmart.earloft.core.data.wear.WatchFeedContent
import de.kleinabersmart.earloft.core.data.wear.WatchSpokenContent
import de.kleinabersmart.earloft.core.data.wear.WatchSpokenKey
import de.kleinabersmart.earloft.core.wearsync.WearSpokenKeyDto
import de.kleinabersmart.earloft.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearPodcastManifestBuilderTest {

    private val feed = WatchFeedContent("f1", "https://example.org/feed", "Podcast", "Author", null, newestFirst = false, continuous = true)

    private fun episode(guid: String, positionMs: Long, audioUrl: String? = "https://example.org/$guid.mp3") = WatchEpisodeContent(
        bookId = guid.hashCode().toLong(),
        feedId = "f1",
        guid = guid,
        title = "Episode $guid",
        publishedAt = 1,
        durationMs = 3_600_000,
        audioUrl = audioUrl,
        mimeType = "audio/mpeg",
        coverUrl = null,
        progress = BookProgress(positionMs, 3_600_000, finished = false, speed = 1.25f, updatedAt = positionMs),
    )

    @Test
    fun `order, subscription and progress are carried over`() {
        val manifest = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("b", 10), episode("a", 20, audioUrl = null))))

        assertEquals(listOf("b", "a"), manifest.episodes.map { it.guid })
        assertEquals("https://example.org/feed", manifest.feeds.single().url)
        assertEquals(1.25f, manifest.episodes.first().progress!!.speed)
        // Episodes from the device are only available via the file channel.
        assertNull(manifest.episodes.last().audioUrl)
    }

    @Test
    fun `ones finished on the phone go along by their key`() {
        val manifest = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), finished = listOf(WatchSpokenKey("f1", "c"))))

        assertEquals(listOf(WearSpokenKeyDto("f1", "c")), manifest.finished)
    }

    @Test
    fun `without progress manifests that differ only in progress are equal`() {
        val before = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("a", 10))))
        val playing = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("a", 15_000))))
        val otherEpisode = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("b", 10))))

        assertNotEquals(before, playing)
        assertEquals(WearPodcastManifestBuilder.withoutProgress(before), WearPodcastManifestBuilder.withoutProgress(playing))
        assertNotEquals(WearPodcastManifestBuilder.withoutProgress(before), WearPodcastManifestBuilder.withoutProgress(otherEpisode))
    }
}
