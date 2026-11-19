package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `clearAfter drops what was still pending and keeps what lay before`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = listOf(5, 6)) }

        reserve.clearAfter()
        reserve.append(listOf(7))

        assertEquals(listOf(0, 1, 7) to 1, reserve.complete(listOf(1), windowIndex = 0))
    }

    @Test
    fun `complete puts the window between before and after and moves the index`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0, 1), after = listOf(5, 6)) }

        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6) to 3, reserve.complete(listOf(2, 3, 4), windowIndex = 1))
    }

    @Test
    fun `played items taken out of the window go behind what lay before it`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = listOf(6, 7)) }

        reserve.keepBefore(listOf(1, 2))
        val next = reserve.take(2)

        assertEquals(listOf(6, 7), next)
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7) to 4, reserve.complete(listOf(3, 4, 5, 6, 7), windowIndex = 1))
    }

    @Test
    fun `complete keeps an unset index unset`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = emptyList()) }

        assertEquals(listOf(0, 1) to -1, reserve.complete(listOf(1), windowIndex = -1))
    }

    @Test
    fun `nextRound hands out the beginning again and complete turns the list back`() {
        // Ten titles, the receiver holds 4..9; 0..3 lay before the window or were played and taken out.
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0, 1), after = emptyList()) }
        reserve.keepBefore(listOf(2, 3))

        assertTrue(reserve.nextRound())
        assertEquals(listOf(0, 1), reserve.take(2))
        // 4..6 played and taken out, the receiver now holds 7, 8, 9, 0, 1 and plays 9.
        reserve.keepBefore(listOf(4, 5, 6))

        assertEquals((0..9).toList() to 9, reserve.complete(listOf(7, 8, 9, 0, 1), windowIndex = 2))
        assertEquals(listOf(2, 3), reserve.take(5))
    }

    @Test
    fun `nextRound does nothing while titles are pending or nothing lay before`() {
        val pending = QueueReserve<Int>().apply { reset(before = listOf(0), after = listOf(5)) }
        val nothingBefore = QueueReserve<Int>().apply { reset(before = emptyList(), after = emptyList()) }

        assertFalse(pending.nextRound())
        assertFalse(nothingBefore.nextRound())
        assertEquals(listOf(0, 1, 5) to 1, pending.complete(listOf(1), windowIndex = 0))
    }

    @Test
    fun `reset replaces everything`() {
        val reserve = QueueReserve<Int>().apply { reset(before = listOf(0), after = listOf(9)) }

        reserve.clear()

        assertTrue(reserve.isEmpty())
        assertEquals(listOf(1) to 0, reserve.complete(listOf(1), windowIndex = 0))
    }
}
