package keinabersmart.kleinaberlaut.core.data.podcast

import org.junit.Assert.assertEquals
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
}
