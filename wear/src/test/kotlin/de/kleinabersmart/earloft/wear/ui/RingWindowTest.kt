package de.kleinabersmart.earloft.wear.ui

import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingWindowTest {

    private val book = SpokenEntry(bookId = 7, trackIndex = 0, title = "Buch", author = "Autor")
    private val chapter = BookChapter(index = 2, title = "Drei", startMs = 600_000, endMs = 900_000)

    @Test
    fun `a book with chapters shows only the current chapter`() {
        assertEquals(RingWindow(600_000, 300_000), ringWindow(book, chapter))
    }

    @Test
    fun `music, episodes and books without chapters show everything`() {
        assertNull(ringWindow(null, chapter))
        assertNull(ringWindow(book.copy(isEpisode = true), chapter))
        assertNull(ringWindow(book, null))
        assertNull(ringWindow(book, chapter.copy(endMs = chapter.startMs)))
    }

    @Test
    fun `book positions map into the chapter and are clamped to it`() {
        val window = RingWindow(600_000, 300_000)

        assertEquals(0L, window.toRing(600_000))
        assertEquals(120_000L, window.toRing(720_000))
        // Shortly before or after the chapter change, before the view knows the new chapter.
        assertEquals(0L, window.toRing(599_000))
        assertEquals(300_000L, window.toRing(901_000))
    }

    @Test
    fun `seeking in the ring lands in the book and stays inside the chapter`() {
        val window = RingWindow(600_000, 300_000)

        assertEquals(600_000L, window.toBook(0))
        assertEquals(720_000L, window.toBook(120_000))
        assertEquals(899_999L, window.toBook(300_000))
        assertEquals(600_000L, window.toBook(-5))
    }
}
