package de.kleinabersmart.earloft.core.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueWindowTest {

    private val items = (0 until 100).toList()

    @Test
    fun `short queues stay untouched`() {
        assertEquals(QueueWindow(items, 42), queueWindow(items, 42, maxSize = 100, shuffle = true))
    }

    @Test
    fun `in order the window starts at the start item and keeps the rest around it`() {
        assertEquals(
            QueueWindow((90 until 100).toList(), 0, before = (0 until 90).toList()),
            queueWindow(items, 90, maxSize = 50, shuffle = false),
        )
        assertEquals(
            QueueWindow((10 until 15).toList(), 0, before = (0 until 10).toList(), after = (15 until 100).toList()),
            queueWindow(items, 10, maxSize = 5, shuffle = false),
        )
    }

    @Test
    fun `shuffled the start item is kept and the rest is sampled from the whole list`() {
        val window = queueWindow(items, 70, maxSize = 50, shuffle = true, random = Random(1))

        assertEquals(50, window.items.size)
        assertEquals(70, window.items[window.startIndex])
        assertEquals(window.items.sorted(), window.items)
        assertEquals(50, window.items.toSet().size)
        assertTrue(window.items.any { it < 20 } && window.items.any { it > 90 })
    }

    @Test
    fun `shuffled everything not chosen lands behind the window`() {
        val window = queueWindow(items, 70, maxSize = 50, shuffle = true, random = Random(1))

        assertTrue(window.before.isEmpty())
        assertEquals(50, window.after.size)
        assertEquals(items, (window.items + window.after).sorted())
    }

    @Test
    fun `oldestPlayed takes the items played longest ago`() {
        // In order: the items before the current one, the first ones go first.
        assertEquals(listOf(0, 1, 2), oldestPlayed(current = 40, count = 3) { it - 1 })
        // Not more than were played.
        assertEquals(listOf(0, 1), oldestPlayed(current = 2, count = 25) { it - 1 })
        assertEquals(emptyList<Int>(), oldestPlayed(current = 0, count = 5) { it - 1 })
    }

    @Test
    fun `oldestPlayed follows the shuffled order`() {
        // Shuffled order 3, 0, 4, 1 (current), 2.
        val order = listOf(3, 0, 4, 1, 2)
        val previous = { index: Int -> order.indexOf(index).let { if (it <= 0) -1 else order[it - 1] } }

        assertEquals(listOf(0, 3), oldestPlayed(current = 1, count = 2, previous = previous))
        assertEquals(listOf(0, 3, 4), oldestPlayed(current = 1, count = 5, previous = previous))
    }
}
