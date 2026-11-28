package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Test

/** "Undo" after taking episodes out of the queue puts back only those, at their old places (review5 8.12). */
class ReinsertAtOldPlacesTest {

    @Test
    fun `the removed ones go back after their earlier neighbors`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), reinsertAtOldPlaces(listOf(1, 3, 5), before = listOf(1, 2, 3, 4, 5), removed = setOf(2, 4)))
    }

    @Test
    fun `what changed meanwhile stays`() {
        // Cleared meanwhile except the loaded 1, then 9 was added: only 2 and 3 come back, 4 and 5 stay out.
        assertEquals(listOf(1L, 2L, 3L, 9L), reinsertAtOldPlaces(listOf(1, 9), before = listOf(1, 2, 3, 4, 5), removed = setOf(2, 3)))
        // Reordered meanwhile: 3 moved to the front stays there, 2 follows its old neighbor 1.
        assertEquals(listOf(3L, 1L, 2L), reinsertAtOldPlaces(listOf(3, 1), before = listOf(1, 2, 3), removed = setOf(2)))
    }

    @Test
    fun `without an earlier neighbor it goes to the front, an appended copy is not doubled`() {
        assertEquals(listOf(1L, 7L, 8L), reinsertAtOldPlaces(listOf(7, 8, 1), before = listOf(1, 2), removed = setOf(1)))
    }
}
