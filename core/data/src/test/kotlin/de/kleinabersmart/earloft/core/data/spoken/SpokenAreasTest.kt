package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.SpokenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenAreasTest {

    @Test
    fun `without a setting all areas are on`() {
        assertEquals(SpokenMode.entries, enabledAreas(null))
        assertEquals(SpokenMode.entries, enabledAreas(emptySet()))
    }

    @Test
    fun `switched off areas are missing, the order stays`() {
        assertEquals(listOf(SpokenMode.MUSIC, SpokenMode.BOOKS), enabledAreas(setOf("PODCASTS")))
    }

    @Test
    fun `if all are off, all apply`() {
        assertEquals(SpokenMode.entries, enabledAreas(SpokenMode.entries.map { it.name }.toSet()))
    }

    @Test
    fun `the last switched on area stays on`() {
        assertEquals(setOf("MUSIC"), hiddenAfter(emptySet(), SpokenMode.MUSIC, enabled = false))
        assertNull(hiddenAfter(setOf("MUSIC", "PODCASTS"), SpokenMode.BOOKS, enabled = false))
        assertEquals(setOf("MUSIC"), hiddenAfter(setOf("MUSIC", "PODCASTS"), SpokenMode.PODCASTS, enabled = true))
    }

    @Test
    fun `a switched off saved area gives way to the first switched on one`() {
        assertEquals(SpokenMode.PODCASTS, shownMode("PODCASTS", SpokenMode.entries))
        assertEquals(SpokenMode.PODCASTS, shownMode("MUSIC", listOf(SpokenMode.PODCASTS, SpokenMode.BOOKS)))
        assertEquals(SpokenMode.BOOKS, shownMode("SPOKEN", SpokenMode.entries))
        assertEquals(SpokenMode.BOOKS, shownMode(null, listOf(SpokenMode.BOOKS)))
    }
}
