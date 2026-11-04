package keinabersmart.kleinaberlaut.core.data.podcast

import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookProgress
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeOrderTest {

    private val order = listOf(1L, 2L, 3L, 4L)

    @Test
    fun `without a current episode the first unheard one starts`() {
        assertEquals(1L, nextEpisode(order, emptySet()))
        assertEquals(3L, nextEpisode(order, setOf(1L, 2L)))
    }

    @Test
    fun `when everything is heard it starts from the beginning`() {
        assertEquals(1L, nextEpisode(order, order.toSet()))
    }

    @Test
    fun `after an episode comes the next unheard one`() {
        assertEquals(2L, nextEpisode(order, emptySet(), currentId = 1L))
        assertEquals(4L, nextEpisode(order, setOf(3L), currentId = 2L))
    }

    @Test
    fun `earlier unheard episodes are not revisited and the end stops`() {
        assertEquals(null, nextEpisode(order, emptySet(), currentId = 4L))
        assertEquals(null, nextEpisode(order, setOf(4L), currentId = 3L))
    }

    @Test
    fun `an unknown current episode yields nothing`() {
        assertEquals(null, nextEpisode(order, emptySet(), currentId = 99L))
        assertEquals(null, nextEpisode(emptyList(), emptySet()))
    }

    /** Folgen wie aus `episodes(feedId)`: die neueste zuerst. */
    private val newestFirst = listOf(episode(3, finished = false), episode(2, finished = true), episode(1, finished = false))

    @Test
    fun `the next unheard episode follows the play order of the subscription`() {
        assertEquals(3L, newestFirst.firstUnheard(newestFirst = true)?.book?.id)
        assertEquals(1L, newestFirst.firstUnheard(newestFirst = false)?.book?.id)
    }

    @Test
    fun `a started episode counts as unheard, a finished one does not`() {
        val list = listOf(episode(2, finished = true), episode(1, finished = false, positionMs = 60_000))
        assertEquals(1L, list.firstUnheard(newestFirst = true)?.book?.id)
    }

    @Test
    fun `when everything is heard there is no next unheard episode`() {
        assertNull(listOf(episode(2, finished = true), episode(1, finished = true)).firstUnheard(newestFirst = false))
        assertNull(emptyList<BookWithProgress>().firstUnheard(newestFirst = true))
    }

    private fun episode(id: Long, finished: Boolean, positionMs: Long = if (finished) 600_000 else 0) = BookWithProgress(
        Book(id = id, serverId = "feed", kind = BookKind.EPISODE, title = "Folge $id", author = "Podcast", durationMs = 600_000, remoteId = "guid-$id"),
        BookProgress(positionMs = positionMs, durationMs = 600_000, finished = finished, speed = 1f, updatedAt = 0),
    )
}
