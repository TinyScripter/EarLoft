package de.kleinabersmart.earloft.core.model

import kotlin.math.max
import kotlin.math.min

/** Color families of cover colors, in the order of the color wheel; [NEUTRAL] and [NONE] come last. */
enum class ColorFamily { RED, ORANGE, YELLOW, GREEN, CYAN, BLUE, PURPLE, PINK, NEUTRAL, NONE }

/**
 * Fallback of the Material color scoring for covers without a usable color (Google blue); such covers are black and white
 * rather than blue.
 */
const val SCORE_FALLBACK_COLOR: Int = 0xFF4285F4.toInt()

private const val MIN_SATURATION = 0.18f
private const val MIN_VALUE = 0.15f

/** Hue (0..360), saturation and value (0..1) of an ARGB color. */
private data class Hsv(val hue: Float, val saturation: Float, val value: Float)

private fun hsvOf(argb: Int): Hsv {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val max = max(r, max(g, b))
    val min = min(r, min(g, b))
    val delta = max - min
    val hue = when {
        delta == 0f -> 0f
        max == r -> 60f * (((g - b) / delta).mod(6f))
        max == g -> 60f * ((b - r) / delta + 2f)
        else -> 60f * ((r - g) / delta + 4f)
    }
    return Hsv(hue, if (max == 0f) 0f else delta / max, max)
}

private fun isNeutral(argb: Int, hsv: Hsv): Boolean =
    argb == SCORE_FALLBACK_COLOR || hsv.saturation < MIN_SATURATION || hsv.value < MIN_VALUE

/** The family of a cover color; `null` (not computed yet) is [ColorFamily.NONE]. */
fun colorFamilyOf(argb: Int?): ColorFamily {
    if (argb == null) return ColorFamily.NONE
    val hsv = hsvOf(argb)
    if (isNeutral(argb, hsv)) return ColorFamily.NEUTRAL
    val h = hsv.hue
    return when {
        h < 15f || h >= 345f -> ColorFamily.RED
        h < 45f -> ColorFamily.ORANGE
        h < 70f -> ColorFamily.YELLOW
        h < 165f -> ColorFamily.GREEN
        h < 200f -> ColorFamily.CYAN
        h < 260f -> ColorFamily.BLUE
        h < 300f -> ColorFamily.PURPLE
        else -> ColorFamily.PINK
    }
}

/**
 * Albums by cover color: around the color wheel starting at red, within a hue light before dark; neutral colors (light to
 * dark) after that, albums without a color last. Equal colors keep their order (the caller sorts by title beforehand).
 */
fun List<Album>.sortedByCoverColor(): List<Album> = sortedWith(
    compareBy<Album> { colorFamilyOf(it.seedColor).ordinal }
        .thenBy { album ->
            val color = album.seedColor ?: return@thenBy 0f
            val hsv = hsvOf(color)
            // Red spans 345..15 degrees: shift so it sorts as one block at the start.
            if (colorFamilyOf(color) == ColorFamily.NEUTRAL) -hsv.value else (hsv.hue + 15f) % 360f
        }
        .thenBy { album -> album.seedColor?.let { -hsvOf(it).value } ?: 0f },
)
