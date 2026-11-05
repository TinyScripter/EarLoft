package de.kleinabersmart.tinyamp.core.data.spoken

import de.kleinabersmart.tinyamp.core.model.SpokenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpokenAreasTest {

    @Test
    fun `ohne Einstellung sind alle Bereiche an`() {
        assertEquals(SpokenMode.entries, enabledAreas(null))
        assertEquals(SpokenMode.entries, enabledAreas(emptySet()))
    }

    @Test
    fun `ausgeschaltete Bereiche fehlen, die Reihenfolge bleibt`() {
        assertEquals(listOf(SpokenMode.MUSIC, SpokenMode.BOOKS), enabledAreas(setOf("PODCASTS")))
    }

    @Test
    fun `sind alle aus, gelten alle`() {
        assertEquals(SpokenMode.entries, enabledAreas(SpokenMode.entries.map { it.name }.toSet()))
    }

    @Test
    fun `der letzte eingeschaltete Bereich bleibt an`() {
        assertEquals(setOf("MUSIC"), hiddenAfter(emptySet(), SpokenMode.MUSIC, enabled = false))
        assertNull(hiddenAfter(setOf("MUSIC", "PODCASTS"), SpokenMode.BOOKS, enabled = false))
        assertEquals(setOf("MUSIC"), hiddenAfter(setOf("MUSIC", "PODCASTS"), SpokenMode.PODCASTS, enabled = true))
    }

    @Test
    fun `ein ausgeschalteter gespeicherter Bereich weicht dem ersten eingeschalteten`() {
        assertEquals(SpokenMode.PODCASTS, shownMode("PODCASTS", SpokenMode.entries))
        assertEquals(SpokenMode.PODCASTS, shownMode("MUSIC", listOf(SpokenMode.PODCASTS, SpokenMode.BOOKS)))
        assertEquals(SpokenMode.BOOKS, shownMode("SPOKEN", SpokenMode.entries))
        assertEquals(SpokenMode.BOOKS, shownMode(null, listOf(SpokenMode.BOOKS)))
    }
}
