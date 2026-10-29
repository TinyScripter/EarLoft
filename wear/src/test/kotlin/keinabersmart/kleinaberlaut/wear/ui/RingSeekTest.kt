package keinabersmart.kleinaberlaut.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class RingSeekTest {

    @Test
    fun `Anfang und Ende des Rings`() {
        assertEquals(0f, ringProgress(295f), 0.001f)
        assertEquals(1f, ringProgress(245f), 0.001f)
    }

    @Test
    fun `Punkte auf dem Ring im Uhrzeigersinn`() {
        assertEquals(0.2097f, ringProgress(0f), 0.001f) // drei Uhr
        assertEquals(0.5f, ringProgress(90f), 0.001f) // sechs Uhr
        assertEquals(0.7903f, ringProgress(180f), 0.001f) // neun Uhr
    }

    @Test
    fun `die Lücke oben gehört ohne Vorgänger zur näheren Seite`() {
        assertEquals(1f, ringProgress(255f), 0.001f)
        assertEquals(0f, ringProgress(285f), 0.001f)
    }

    @Test
    fun `in der Lücke bleibt das Ziehen auf der Seite, von der es kam`() {
        assertEquals(1f, ringProgress(270f, previous = 0.9f), 0.001f)
        assertEquals(0f, ringProgress(270f, previous = 0.1f), 0.001f)
    }

    @Test
    fun `ein Sprung über die Lücke wird nicht zum Sprung des Songs`() {
        // Der Finger zieht von 95 % direkt hinter die Lücke (2 %): Am Ende bleiben statt zum Anfang springen.
        assertEquals(1f, ringProgress(301.2f, previous = 0.95f), 0.001f)
        assertEquals(0f, ringProgress(240f, previous = 0.05f), 0.001f)
    }

    @Test
    fun `normale Bewegung folgt dem Finger`() {
        assertEquals(0.5f, ringProgress(90f, previous = 0.45f), 0.001f)
    }

    @Test
    fun `Zeit wird als Minuten und Sekunden formatiert`() {
        assertEquals("0:00", formatPosition(0))
        assertEquals("1:05", formatPosition(65_000))
        assertEquals("1:02:05", formatPosition(3_725_000))
        assertEquals("0:00", formatPosition(-5_000))
    }
}
