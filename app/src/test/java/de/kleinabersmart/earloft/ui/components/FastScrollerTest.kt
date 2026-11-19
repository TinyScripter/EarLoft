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

    @Test
    fun `at the end of a short list the first visible row is the reference, not the last row`() {
        // 15 chapters, 6 fit: at the end rows 9..14 are visible, so the thumb is at the bottom with row 9 first.
        assertEquals(9, lastFirstIndex(count = 15, visible = 6))
        // Nothing laid out yet: the last row, as before.
        assertEquals(14, lastFirstIndex(count = 15, visible = 0))
        assertEquals(0, lastFirstIndex(count = 3, visible = 5))
        assertEquals(-1, lastFirstIndex(count = 0, visible = 0))
    }
}
