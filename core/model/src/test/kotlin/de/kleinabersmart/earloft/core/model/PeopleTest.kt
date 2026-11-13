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
}
