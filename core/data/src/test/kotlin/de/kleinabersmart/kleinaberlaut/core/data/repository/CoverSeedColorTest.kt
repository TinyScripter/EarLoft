package de.kleinabersmart.kleinaberlaut.core.data.repository

import org.junit.Assert.assertTrue
import org.junit.Test

class CoverSeedColorTest {

    private fun red(argb: Int) = (argb shr 16) and 0xFF
    private fun blue(argb: Int) = argb and 0xFF

    @Test
    fun `einfarbiges Cover liefert diese Farbe`() {
        val seed = seedColorOf(IntArray(64 * 64) { 0xFFD32F2F.toInt() })
        assertTrue(red(seed) > blue(seed) + 60)
    }

    @Test
    fun `dominante bunte Farbe gewinnt gegen graue Flaeche`() {
        val blue = 0xFF1976D2.toInt()
        val gray = 0xFF808080.toInt()
        val seed = seedColorOf(IntArray(64 * 64) { if (it % 4 == 0) gray else blue })
        assertTrue(blue(seed) > red(seed) + 60)
    }
}
