package de.kleinabersmart.earloft.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Multi-selection in episode lists (concept 12.5). */
class EpisodeSelectionTest {

    @Test
    fun `toggling starts and ends the selection`() {
        val selection = EpisodeSelection(emptyList())
        assertFalse(selection.active)

        selection.toggle(1)
        selection.toggle(2)
        assertTrue(selection.active)
        assertEquals(setOf(1L, 2L), selection.ids)

        selection.toggle(1)
        selection.toggle(2)
        assertFalse(selection.active)
    }

    @Test
    fun `select all selects the shown ones and a second time none`() {
        val selection = EpisodeSelection(listOf(2))

        selection.toggleAll(listOf(1, 2, 3))
        assertEquals(setOf(1L, 2L, 3L), selection.ids)

        selection.toggleAll(listOf(1, 2, 3))
        assertFalse(selection.active)
    }

    @Test
    fun `episodes that leave the list leave the selection`() {
        val selection = EpisodeSelection(listOf(1, 2, 3))

        selection.retain(listOf(2, 3, 4))

        assertEquals(setOf(2L, 3L), selection.ids)
    }

    @Test
    fun `deleting downloads asks from two episodes onwards`() {
        assertFalse(multiDownloadDeleteNeedsConfirmation(0))
        assertFalse(multiDownloadDeleteNeedsConfirmation(1))
        assertTrue(multiDownloadDeleteNeedsConfirmation(2))
        assertTrue(multiDownloadDeleteNeedsConfirmation(10))
    }
}
