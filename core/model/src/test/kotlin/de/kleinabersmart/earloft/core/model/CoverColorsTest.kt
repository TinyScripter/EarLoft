package de.kleinabersmart.earloft.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class CoverColorsTest {

    private fun album(id: Long, color: Int?) = Album(id, "Album $id", "Artist", null, 1, seedColor = color)

    @Test
    fun `families follow the color wheel`() {
        assertEquals(ColorFamily.RED, colorFamilyOf(0xFFE53935.toInt()))
        assertEquals(ColorFamily.RED, colorFamilyOf(0xFFD81B4A.toInt()))
        assertEquals(ColorFamily.ORANGE, colorFamilyOf(0xFFFB8C00.toInt()))
        assertEquals(ColorFamily.YELLOW, colorFamilyOf(0xFFFDD835.toInt()))
        assertEquals(ColorFamily.GREEN, colorFamilyOf(0xFF43A047.toInt()))
        assertEquals(ColorFamily.CYAN, colorFamilyOf(0xFF00ACC1.toInt()))
        assertEquals(ColorFamily.BLUE, colorFamilyOf(0xFF1E88E5.toInt()))
        assertEquals(ColorFamily.PURPLE, colorFamilyOf(0xFF8E24AA.toInt()))
        assertEquals(ColorFamily.PINK, colorFamilyOf(0xFFEC407A.toInt()))
    }

    @Test
    fun `grey, black, white and the scoring fallback are neutral, missing is none`() {
        assertEquals(ColorFamily.NEUTRAL, colorFamilyOf(0xFF808080.toInt()))
        assertEquals(ColorFamily.NEUTRAL, colorFamilyOf(0xFF0A0A20.toInt()))
        assertEquals(ColorFamily.NEUTRAL, colorFamilyOf(0xFFFAFAFA.toInt()))
        assertEquals(ColorFamily.NEUTRAL, colorFamilyOf(SCORE_FALLBACK_COLOR))
        assertEquals(ColorFamily.NONE, colorFamilyOf(null))
    }

    @Test
    fun `sorts around the wheel, neutral light to dark, then albums without a color in their order`() {
        val albums = listOf(
            album(1, null),
            album(2, 0xFF1E88E5.toInt()), // blue
            album(3, 0xFF202020.toInt()), // dark grey
            album(4, 0xFFE53935.toInt()), // red
            album(5, null),
            album(6, 0xFFF0F0F0.toInt()), // light grey
            album(7, 0xFFD81B4A.toInt()), // red just below 360 degrees
            album(8, 0xFFFDD835.toInt()), // yellow
        )
        assertEquals(listOf(7L, 4, 8, 2, 6, 3, 1, 5), albums.sortedByCoverColor().map { it.id })
    }
}
