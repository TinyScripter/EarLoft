package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/** Filter, Reihenfolge und „Folgen behalten“ der Podcast-Listen. */
class PodcastListsTest {

    private fun episode(id: Long, feed: String = "f", progress: BookProgress? = null) = BookWithProgress(
        Book(id = id, serverId = feed, kind = BookKind.EPISODE, title = "$id", author = feed, durationMs = 1_000, remoteId = "$id"),
        progress,
    )

    private fun progress(positionMs: Long, finished: Boolean = false) =
        BookProgress(positionMs = positionMs, durationMs = 1_000, finished = finished, updatedAt = 1)

    private fun feed(id: String) = PodcastFeed(id, "https://example.org/$id", id, "", "", null, autoDownload = false, lastRefreshAt = 0)

    // Neueste zuerst: 5 neu, 4 angefangen, 3 gehört, 2 neu, 1 geladen.
    private val episodes = listOf(
        episode(5),
        episode(4, progress = progress(300)),
        episode(3, progress = progress(1_000, finished = true)),
        episode(2),
        episode(1),
    )

    @Test
    fun `filters keep the order and oldest first reverses it`() {
        fun ids(filter: EpisodeFilter, oldestFirst: Boolean = false) =
            episodes.filterEpisodes(filter, oldestFirst, downloaded = setOf(1L)).map { it.book.id }

        assertEquals(listOf(5L, 4L, 3L, 2L, 1L), ids(EpisodeFilter.ALL))
        assertEquals(listOf(5L, 4L, 2L, 1L), ids(EpisodeFilter.UNHEARD))
        assertEquals(listOf(4L), ids(EpisodeFilter.STARTED))
        assertEquals(listOf(1L), ids(EpisodeFilter.DOWNLOADED))
        assertEquals(listOf(1L, 2L, 4L, 5L), ids(EpisodeFilter.UNHEARD, oldestFirst = true))
    }

    @Test
    fun `keeping the newest hides only older untouched episodes and counts per feed`() {
        val mixed = listOf(episode(9, "g")) + episodes + listOf(episode(8, "g"))
        val kept = mixed.keepNewest({ it.book.serverId }, { if (it == "f") 2 else 0 }) {
            it.progress == null || (!it.progress.finished && it.progress.positionMs <= 0)
        }

        // Von „f“ die zwei neuesten, dazu die angefangene und die gehörte; „g“ behält alles.
        assertEquals(listOf(9L, 5L, 4L, 3L, 8L), kept.map { it.book.id })
        assertEquals(mixed, mixed.keepNewest({ it.book.serverId }, { 0 }) { true })
    }

    @Test
    fun `subscriptions sort by their numbers and keep the name order on ties`() {
        val feeds = listOf(feed("a"), feed("b"), feed("c"))
        val stats = mapOf(
            "a" to FeedStats(newestEpisodeAt = 10, lastPlayedAt = 0, newEpisodes = 1),
            "b" to FeedStats(newestEpisodeAt = 30, lastPlayedAt = 5, newEpisodes = 1),
            "c" to FeedStats(newestEpisodeAt = 20, lastPlayedAt = 7, newEpisodes = 4),
        )

        fun ids(sort: FeedSort) = feeds.sortedFeeds(sort, stats).map { it.id }

        assertEquals(listOf("a", "b", "c"), ids(FeedSort.NAME))
        assertEquals(listOf("b", "c", "a"), ids(FeedSort.NEWEST_EPISODE))
        assertEquals(listOf("c", "b", "a"), ids(FeedSort.RECENTLY_PLAYED))
        assertEquals(listOf("c", "a", "b"), ids(FeedSort.MOST_NEW))
    }
}
