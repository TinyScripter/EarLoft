package de.kleinabersmart.tinyamp.core.playback

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
}
