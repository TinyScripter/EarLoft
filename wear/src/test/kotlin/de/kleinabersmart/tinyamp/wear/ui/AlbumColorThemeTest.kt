package de.kleinabersmart.tinyamp.wear.ui

import androidx.wear.compose.material3.ColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlbumColorThemeTest {

    private val base = ColorScheme()
    private val red = 0xFFE53935.toInt()
    private val blue = 0xFF1E88E5.toInt()

    @Test
    fun `die Akzentfarbe folgt der Grundfarbe des Albums`() {
        val redScheme = albumColorScheme(base, red)
        val blueScheme = albumColorScheme(base, blue)

        assertNotEquals(redScheme.primary, blueScheme.primary)
        assertTrue(redScheme.primary.red > redScheme.primary.blue)
        assertTrue(blueScheme.primary.blue > blueScheme.primary.red)
    }

    @Test
    fun `Fehler- und Textfarben bleiben unverändert`() {
        val scheme = albumColorScheme(base, red)

        assertEquals(base.error, scheme.error)
        assertEquals(base.onSurface, scheme.onSurface)
        assertEquals(base.background, scheme.background)
    }

    @Test
    fun `Schrift auf der Akzentfarbe bleibt lesbar`() {
        val scheme = albumColorScheme(base, red)

        // Dunkles Schema: heller Akzent, dunkle Schrift darauf.
        assertTrue(scheme.onPrimary.luminance() < scheme.primary.luminance())
    }

    private fun androidx.compose.ui.graphics.Color.luminance() = 0.2126f * red + 0.7152f * green + 0.0722f * blue
}
