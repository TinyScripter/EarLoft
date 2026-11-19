package de.kleinabersmart.earloft.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/** Robolectric for the ICU formatters (`android.icu`). Spaces are normalised: ICU uses (narrow) no-break spaces. */
@RunWith(RobolectricTestRunner::class)
class FormattingTest {

    private fun String.plainSpaces() = replace(' ', ' ').replace(' ', ' ')

    private fun bytes(value: Long, locale: Locale) = formatBytes(value, locale).plainSpaces()

    @Test
    fun `bytes use SI units in english`() {
        assertEquals("0 byte", bytes(0, Locale.US))
        assertEquals("999 byte", bytes(999, Locale.US))
        assertEquals("1.5 kB", bytes(1_500, Locale.US))
        assertEquals("4.6 GB", bytes(4_600_000_000, Locale.US))
        assertEquals("250 MB", bytes(250_000_000, Locale.US))
    }

    @Test
    fun `german uses a decimal comma`() {
        assertEquals("1,5 kB", bytes(1_500, Locale.GERMANY))
        assertEquals("4,6 GB", bytes(4_600_000_000, Locale.GERMANY))
    }

    @Test
    fun `french uses its own unit abbreviations`() {
        assertEquals("1,5 Go", bytes(1_500_000_000, Locale.FRANCE))
    }

    @Test
    fun `values just below the next unit switch to it`() {
        assertEquals("1.0 MB", bytes(999_960, Locale.US))
        assertEquals("100 MB", bytes(99_960_000, Locale.US))
    }

    @Test
    fun `negative sizes count as zero`() {
        assertEquals("0 byte", bytes(-5, Locale.US))
    }

    @Test
    fun `percentages follow the locale`() {
        assertEquals("42%", formatPercent(0.42, Locale.US).plainSpaces())
        assertEquals("42 %", formatPercent(0.42, Locale.GERMANY).plainSpaces())
        assertEquals("100%", formatPercent(1.7, Locale.US).plainSpaces())
    }
}
