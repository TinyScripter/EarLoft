package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.AuthorHit
import de.kleinabersmart.earloft.core.model.Book
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.parsePeople
import org.junit.Assert.assertEquals
import org.junit.Test

/** Several authors or narrators of a book (`books_improvements.md` F3). */
class BookPeopleGroupsTest {

    private fun book(id: Long, title: String, author: String, narrator: String = "") = BookWithProgress(
        Book(id = id, serverId = "abs", kind = BookKind.BOOK, title = title, author = author, narrator = narrator, durationMs = 1, remoteId = "r$id"),
        null,
    )

    private val books = listOf(
        book(1, "Tote Seelen singen nicht", "Line Holm, Stine Bolther, Jussi Adler-Olsen", "Wolfram Koch"),
        book(2, "Verheißung", "Jussi Adler-Olsen", "Wolfram Koch"),
        book(3, "Kill Shot", "Günther Frauenlob — Translator, Jo Nesbø", "Uve Teschner, Anna Thalbach"),
        book(4, "Ohne Autor", ""),
    )

    @Test
    fun `a book of several authors stands with each of them, translators get no group`() {
        val groups = books.authorGroups().associate { group -> group.name to group.books.map { it.book.id } }
        assertEquals(
            mapOf(
                "Jo Nesbø" to listOf(3L),
                "Jussi Adler-Olsen" to listOf(1L, 2L),
                "Line Holm" to listOf(1L),
                "Stine Bolther" to listOf(1L),
            ),
            groups,
        )
        assertEquals(listOf("Jo Nesbø", "Jussi Adler-Olsen", "Line Holm", "Stine Bolther"), books.authorGroups().map { it.name })
    }

    @Test
    fun `several narrators each get the book`() {
        val groups = books.narratorGroups().associate { group -> group.name to group.books.map { it.book.id } }
        assertEquals(mapOf("Anna Thalbach" to listOf(3L), "Uve Teschner" to listOf(3L), "Wolfram Koch" to listOf(1L, 2L)), groups)
    }

    @Test
    fun `search hits are single names that match the query`() {
        val rows = books.map { it.book.id to it.book.author }
        assertEquals(listOf(AuthorHit("Jussi Adler-Olsen", 1, 2)), peopleHits(rows, "adler", 10, ::bookAuthors))
        // The entry matches, but the translator doesn't count as an author.
        assertEquals(emptyList<AuthorHit>(), peopleHits(rows, "frauenlob", 10, ::bookAuthors))
        assertEquals(listOf(AuthorHit("Line Holm", 1, 1)), peopleHits(rows, "holm", 10, ::bookAuthors))
        val narrators = books.map { it.book.id to it.book.narrator }
        assertEquals(listOf(AuthorHit("Anna Thalbach", 3, 1)), peopleHits(narrators, "thal", 10, ::parsePeople))
        assertEquals(2, peopleHits(rows, "s", 2, ::bookAuthors).size)
    }
}
