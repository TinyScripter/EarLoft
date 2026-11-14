package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PeopleTest {

    @Test
    fun `several authors from Audiobookshelf become single names`() {
        assertEquals(listOf("Line Holm", "Stine Bolther", "Jussi Adler-Olsen"), parsePeople("Line Holm, Stine Bolther, Jussi Adler-Olsen"))
        assertEquals(listOf("A", "B"), parsePeople(" A; B ,, A "))
        assertEquals(listOf("Kalk & Welk"), parsePeople("Kalk & Welk"))
        assertEquals(emptyList<String>(), parsePeople("  "))
    }

    @Test
    fun `translators and editors are no authors of their own`() {
        assertTrue(hasContributorRole("Günther Frauenlob - Übersetzer"))
        assertTrue(hasContributorRole("Ruggero Leò - translator"))
        assertTrue(hasContributorRole("Günther Frauenlob — Translator"))
        assertFalse(hasContributorRole("Jo Nesbø"))
        assertEquals(listOf("Jo Nesbø"), bookAuthors("Günther Frauenlob — Translator, Jo Nesbø"))
        assertEquals(listOf("Matt Dinniman"), bookAuthors("Matt Dinniman, Ruggero Leò - translator"))
    }

    @Test
    fun `a book with only contributors keeps them`() {
        assertEquals(listOf("Anna Muster (Herausgeberin)"), bookAuthors("Anna Muster (Herausgeberin)"))
        assertEquals(emptyList<String>(), bookAuthors(""))
    }

    @Test
    fun `tiles show the authors without translators in front`() {
        assertEquals("Jo Nesbø", bookAuthorLine("Günther Frauenlob — Translator, Jo Nesbø"))
        assertEquals("Line Holm, Jussi Adler-Olsen", bookAuthorLine("Line Holm; Jussi Adler-Olsen"))
        assertEquals("", bookAuthorLine(" "))
        val book = Book(1, "s", BookKind.BOOK, "T", "Günther Frauenlob - Übersetzer, Jo Nesbø", durationMs = 0, remoteId = "r")
        assertEquals("Jo Nesbø", book.authorLine)
        // Folgen tragen den Autor des Feeds; der bleibt, wie er ist.
        assertEquals("Sprecher: Anna, Ben", book.copy(kind = BookKind.EPISODE, author = "Sprecher: Anna, Ben").authorLine)
    }
}
