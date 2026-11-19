package de.kleinabersmart.earloft.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.materialkolor.dynamicColorScheme

/**
 * Wear color scheme with the album's accent color ([seedColor], computed once from the cover and stored in `albums.seedColor`,
 * see `AlbumColorRepository`). Without a color the surrounding scheme stays.
 */
@Composable
fun AlbumColorTheme(seedColor: Int?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(seedColor, base) { if (seedColor == null) base else albumColorScheme(base, seedColor) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Takes accent and surface colors from the dark scheme of the seed color; error and text colors stay as in [base]. */
internal fun albumColorScheme(base: ColorScheme, seedColor: Int): ColorScheme {
    val seed = dynamicColorScheme(Color(seedColor), isDark = true, isAmoled = true)
    return base.copy(
        primary = seed.primary,
        primaryDim = seed.primary,
        onPrimary = seed.onPrimary,
        primaryContainer = seed.primaryContainer,
        onPrimaryContainer = seed.onPrimaryContainer,
        secondary = seed.secondary,
        secondaryDim = seed.secondary,
        onSecondary = seed.onSecondary,
        secondaryContainer = seed.secondaryContainer,
        onSecondaryContainer = seed.onSecondaryContainer,
        surfaceContainerLow = seed.surfaceContainerLow,
        surfaceContainer = seed.surfaceContainer,
        surfaceContainerHigh = seed.surfaceContainerHigh,
    )
}
