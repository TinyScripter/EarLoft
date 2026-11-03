package keinabersmart.kleinaberlaut.wear

import keinabersmart.kleinaberlaut.core.model.Book
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import org.junit.Assert.assertEquals
import org.junit.Test

class ContinueListeningMergeTest {

    private fun entry(id: Long, kind: BookKind) =
        BookWithProgress(Book(id, "srv", kind, "Titel $id", "Autor", durationMs = 1_000, remoteId = "$id"), progress = null)

    private val book1 = entry(1, BookKind.BOOK)
    private val book2 = entry(2, BookKind.BOOK)
    private val episode10 = entry(10, BookKind.EPISODE)
    private val episode11 = entry(11, BookKind.EPISODE)

    @Test
    fun `Startreihenfolge gilt fuer Buecher und Folgen gemeinsam`() {
        val merged = mergeContinueListening(listOf(book1, book2), listOf(episode10, episode11), startOrder = listOf(10, 2, 1, 11), limit = 10)
        assertEquals(listOf(10L, 2L, 1L, 11L), merged.map { it.book.id })
    }

    @Test
    fun `Ohne Startreihenfolge erst Buecher, dann Folgen, jeweils wie geliefert`() {
        val merged = mergeContinueListening(listOf(book2, book1), listOf(episode11, episode10), startOrder = listOf(10), limit = 10)
        assertEquals(listOf(10L, 2L, 1L, 11L), merged.map { it.book.id })
    }

    @Test
    fun `Doppelte Eintraege und Grenze`() {
        val merged = mergeContinueListening(listOf(book1), listOf(episode10, episode10, episode11), startOrder = emptyList(), limit = 2)
        assertEquals(listOf(1L, 10L), merged.map { it.book.id })
    }
}
