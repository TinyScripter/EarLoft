package de.kleinabersmart.tinyamp.ui.spoken

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class EpisodeDateTest {

    private fun millis(year: Int, month: Int, day: Int) =
        Calendar.getInstance().apply { clear(); set(year, month, day, 12, 0) }.timeInMillis

    @Test
    fun `the year is left out in the current year`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Oct 31", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.US))
    }

    @Test
    fun `the year is shown for other years`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Oct 31, 2025", formatEpisodeDate(millis(2025, Calendar.OCTOBER, 31), now, Locale.US))
    }

    @Test
    fun `german months are abbreviated before the day`() {
        val now = millis(2026, Calendar.NOVEMBER, 1)
        assertEquals("Okt. 31", formatEpisodeDate(millis(2026, Calendar.OCTOBER, 31), now, Locale.GERMANY))
    }
}
