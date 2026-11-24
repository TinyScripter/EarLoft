package de.kleinabersmart.earloft.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The app colors from the logo orange: readable text on every main color, the accent stays orange, dark is dark. */
class AppColorSchemeTest {

    private val light = appColorScheme(isDark = false)
    private val dark = appColorScheme(isDark = true)

    /** WCAG contrast ratio of two colors (1 to 21). */
    private fun contrast(a: Color, b: Color): Double {
        val lighter = max(a.luminance(), b.luminance())
        val darker = min(a.luminance(), b.luminance())
        return (lighter + 0.05) / (darker + 0.05)
    }

    /** Hue in degrees (0..360) and saturation (0..1) of the HSL model. */
    private fun hue(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        if (delta == 0f) return 0f
        val h = when (max) {
            color.red -> ((color.green - color.blue) / delta).mod(6f)
            color.green -> (color.blue - color.red) / delta + 2
            else -> (color.red - color.green) / delta + 4
        }
        return h * 60
    }

    private fun saturation(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val lightness = (max + min) / 2
        return if (max == min) 0f else (max - min) / (1 - abs(2 * lightness - 1))
    }

    private fun ColorScheme.textPairs() = mapOf(
        "primary" to (primary to onPrimary),
        "primaryContainer" to (primaryContainer to onPrimaryContainer),
        "secondaryContainer" to (secondaryContainer to onSecondaryContainer),
        "surface" to (surface to onSurface),
        "surfaceVariant" to (surfaceVariant to onSurfaceVariant),
        "background" to (background to onBackground),
        "error" to (error to onError),
        "errorContainer" to (errorContainer to onErrorContainer),
    )

    @Test
    fun `text on every main color is readable in light and dark`() {
        for ((name, scheme) in mapOf("light" to light, "dark" to dark)) {
            scheme.textPairs().forEach { (role, pair) ->
                val ratio = contrast(pair.first, pair.second)
                assertTrue("$name $role: contrast $ratio below 4.5", ratio >= 4.5)
            }
        }
    }

    @Test
    fun `the accent is the orange of the logo`() {
        val brand = hue(BrandOrange)
        for (accent in listOf(light.primary, dark.primary, light.primaryContainer)) {
            val distance = abs(hue(accent) - brand).let { min(it, 360 - it) }
            assertTrue("hue ${hue(accent)} is $distance° away from the logo's $brand°", distance <= 25)
        }
    }

    @Test
    fun `surfaces carry less color than the accent`() {
        for (scheme in listOf(light, dark)) {
            assertTrue(saturation(scheme.surfaceContainer) < saturation(scheme.primaryContainer))
            assertTrue(saturation(scheme.surface) < saturation(scheme.primary))
        }
    }

    @Test
    fun `dark is dark and light is light`() {
        assertTrue(dark.background.luminance() < 0.05f)
        assertTrue(light.background.luminance() > 0.8f)
        assertTrue(dark.onBackground.luminance() > light.onBackground.luminance())
    }
}
