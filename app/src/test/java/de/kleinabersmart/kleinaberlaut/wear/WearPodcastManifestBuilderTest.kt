package de.kleinabersmart.kleinaberlaut.wear

import de.kleinabersmart.kleinaberlaut.core.data.wear.WatchEpisodeContent
import de.kleinabersmart.kleinaberlaut.core.data.wear.WatchFeedContent
import de.kleinabersmart.kleinaberlaut.core.data.wear.WatchSpokenContent
import de.kleinabersmart.kleinaberlaut.core.model.BookProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WearPodcastManifestBuilderTest {

    private val feed = WatchFeedContent("f1", "https://example.org/feed", "Podcast", "Autor", null, newestFirst = false, continuous = true)

    private fun episode(guid: String, positionMs: Long, audioUrl: String? = "https://example.org/$guid.mp3") = WatchEpisodeContent(
        bookId = guid.hashCode().toLong(),
        feedId = "f1",
        guid = guid,
        title = "Folge $guid",
        publishedAt = 1,
        durationMs = 3_600_000,
        audioUrl = audioUrl,
        mimeType = "audio/mpeg",
        coverUrl = null,
        progress = BookProgress(positionMs, 3_600_000, finished = false, speed = 1.25f, updatedAt = positionMs),
    )

    @Test
    fun `Reihenfolge, Abo und Fortschritt werden uebernommen`() {
        val manifest = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("b", 10), episode("a", 20, audioUrl = null))))

        assertEquals(listOf("b", "a"), manifest.episodes.map { it.guid })
        assertEquals("https://example.org/feed", manifest.feeds.single().url)
        assertEquals(1.25f, manifest.episodes.first().progress!!.speed)
        // Folgen vom Gerät gibt es nur über den Datei-Kanal.
        assertNull(manifest.episodes.last().audioUrl)
    }

    @Test
    fun `ohne Fortschritt unterscheiden sich nur fortschreitende Manifeste nicht`() {
        val before = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("a", 10))))
        val playing = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("a", 15_000))))
        val otherEpisode = WearPodcastManifestBuilder.build(WatchSpokenContent(listOf(feed), listOf(episode("b", 10))))

        assertNotEquals(before, playing)
        assertEquals(WearPodcastManifestBuilder.withoutProgress(before), WearPodcastManifestBuilder.withoutProgress(playing))
        assertNotEquals(WearPodcastManifestBuilder.withoutProgress(before), WearPodcastManifestBuilder.withoutProgress(otherEpisode))
    }
}
