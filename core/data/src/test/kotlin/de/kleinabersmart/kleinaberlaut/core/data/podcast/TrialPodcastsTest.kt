package de.kleinabersmart.kleinaberlaut.core.data.podcast

import org.junit.Assert.assertEquals
import org.junit.Test

/** Regeln für das Aufräumen der Probeabos (Konzept 12.10). */
class TrialPodcastsTest {

    private fun episode(id: Long, feed: String = "f", finished: Boolean = false, progress: Boolean = false, downloaded: Boolean = false) =
        TrialEpisode(id, feed, finished, progress, downloaded)

    @Test
    fun `a queued episode keeps its feed, a finished one loses progress and download`() {
        val plan = planTrialCleanup(
            feedIds = listOf("f"),
            episodes = listOf(episode(1, progress = true), episode(2, finished = true, progress = true, downloaded = true)),
            queue = listOf(1, 2),
            hidden = emptySet(),
            loadedBookId = null,
            openFeeds = emptySet(),
        )

        assertEquals(TrialCleanup(episodes = listOf(2), feeds = emptyList()), plan)
    }

    @Test
    fun `taking an episode out of the queue forgets it and the feed goes once nothing is left`() {
        val plan = planTrialCleanup(
            feedIds = listOf("f"),
            episodes = listOf(episode(1, progress = true, downloaded = true)),
            queue = listOf(1),
            hidden = setOf(1),
            loadedBookId = null,
            openFeeds = emptySet(),
        )

        assertEquals(TrialCleanup(episodes = emptyList(), feeds = listOf("f")), plan)
    }

    @Test
    fun `an open page keeps the feed but not the forgotten episode`() {
        val plan = planTrialCleanup(
            feedIds = listOf("f"),
            episodes = listOf(episode(1, progress = true), episode(2)),
            queue = emptyList(),
            hidden = emptySet(),
            loadedBookId = null,
            openFeeds = setOf("f"),
        )

        assertEquals(TrialCleanup(episodes = listOf(1), feeds = emptyList()), plan)
    }

    @Test
    fun `the episode loaded in the player stays even when finished or out of the queue`() {
        val plan = planTrialCleanup(
            feedIds = listOf("f", "g"),
            episodes = listOf(episode(1, finished = true, progress = true), episode(2, feed = "g", progress = true)),
            queue = emptyList(),
            hidden = emptySet(),
            loadedBookId = 1,
            openFeeds = emptySet(),
        )

        assertEquals(TrialCleanup(episodes = emptyList(), feeds = listOf("g")), plan)
    }

    @Test
    fun `nothing to do for untouched episodes of an open preview`() {
        val plan = planTrialCleanup(listOf("f"), listOf(episode(1), episode(2)), emptyList(), emptySet(), null, setOf("f"))

        assertEquals(true, plan.isEmpty)
    }

    @Test
    fun `page counts add up and only the last close removes the feed`() {
        val pages = TrialPodcastPages()
        pages.opened("f")
        pages.opened("f")
        pages.closed("f")
        assertEquals(setOf("f"), pages.open.value.keys)
        pages.closed("f")
        assertEquals(emptySet<String>(), pages.open.value.keys)
    }
}
