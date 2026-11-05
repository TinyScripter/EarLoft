package de.kleinabersmart.tinyamp.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueueReserveTest {

    @Test
    fun `take hands out the next items in order until the reserve is empty`() {
        val reserve = QueueReserve<Int>().apply { reset(after = listOf(1, 2, 3, 4, 5)) }

        assertEquals(listOf(1, 2), reserve.take(2))
        assertEquals(3, reserve.remaining)
        assertEquals(listOf(3, 4, 5), reserve.take(10))
        assertEquals(0, reserve.remaining)
        assertEquals(emptyList<Int>(), reserve.take(3))
    }

    @Test
    fun `peek does not remove anything`() {
        val reserve = QueueReserve<Int>().apply { reset(after = listOf(1, 2, 3)) }

        assertEquals(listOf(1, 2), reserve.peek(2))
        assertEquals(3, reserve.remaining)
    }

    @Test
    fun `isEmpty also looks at what lay before the window`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0)) }

        assertEquals(0, reserve.remaining)
        assertTrue(!reserve.isEmpty())
    }

    @Test
    fun `append goes behind what is still pending`() {
        val reserve = QueueReserve<Int>().apply { reset(after = listOf(1, 2)) }

        reserve.append(listOf(3, 4))

        assertEquals(listOf(1, 2, 3, 4), reserve.take(10))
    }

    @Test
    fun `complete puts the window between before and after and moves the index`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0, 1), after = listOf(5, 6)) }

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6) to 3, reserve.complete(listOf(2, 3, 4), windowIndex = 1))
    }

    @Test
    fun `complete keeps an unset index unset`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = emptyList()) }

        assertEquals(listOf(0, 1) to -1, reserve.complete(listOf(1), windowIndex = -1))
    }

    @Test
    fun `reset replaces everything`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = listOf(9)) }

        reserve.clear()

        assertTrue(reserve.isEmpty())
        assertEquals(listOf(1) to 0, reserve.complete(listOf(1), windowIndex = 0))
    }
}
