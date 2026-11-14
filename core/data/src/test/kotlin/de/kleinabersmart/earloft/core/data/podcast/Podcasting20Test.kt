package de.kleinabersmart.earloft.core.data.podcast

import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.EpisodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trailer in Listen und die Kennung beim Feed-Umzug (Konzept 12.5/12.10 „Podcasting 2.0“). */
class Podcasting20Test {

    private fun episode(id: Long, feed: String, type: EpisodeType? = null, starred: Boolean = false) = BookEntity(
        bookId = id,
        serverId = feed,
        kind = BookKind.EPISODE,
        remoteId = "g$id",
        title = "T$id",
        normTitle = "t$id",
        author = feed,
        durationMs = 1000,
        episodeType = type,
        starredAt = if (starred) 1L else null,
    )

    @Test
    fun `trailers disappear once a feed has real episodes`() {
        val list = listOf(
            episode(1, "a"),
            episode(2, "a", EpisodeType.TRAILER),
            episode(3, "a", EpisodeType.TRAILER),
            episode(4, "a", EpisodeType.TRAILER, starred = true),
            episode(5, "b", EpisodeType.TRAILER),
            episode(6, "c", EpisodeType.BONUS),
        )
        // Ein angefangener Trailer bleibt, ebenso ein gemerkter; ein Abo nur mit Trailer zeigt ihn.
        val progress = mapOf(3L to BookProgressEntity(3, 500, 1000, finished = false, updatedAt = 1))
        assertEquals(listOf(1L, 3L, 4L, 5L, 6L), list.withoutTrailers(progress).map { it.bookId })
    }

    @Test
    fun `podcast guid only blocks a move when both differ`() {
        assertTrue(samePodcast(null, "x"))
        assertTrue(samePodcast("x", null))
        assertTrue(samePodcast("ABC", "abc"))
        assertFalse(samePodcast("abc", "def"))
    }
}
