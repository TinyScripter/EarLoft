package de.kleinabersmart.earloft.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class FastScrollerTest {

    @Test
    fun `the letter of a book list follows the sort order, without umlauts and leading punctuation`() {
        assertEquals("O", foldedLetterOf("Ödön von Horváth"))
        assertEquals("E", foldedLetterOf("Émile Zola"))
        assertEquals("T", foldedLetterOf("„Tis"))
        assertEquals("D", foldedLetterOf("de Balzac"))
    }

    @Test
    fun `digits, signs and nothing give a hash`() {
        assertEquals("#", foldedLetterOf("1984"))
        assertEquals("#", foldedLetterOf("???"))
        assertEquals("#", foldedLetterOf(""))
    }

    @Test
    fun `the plain letter keeps the first character as it is`() {
        assertEquals("Ö", letterOf("Ödön"))
    }
}
