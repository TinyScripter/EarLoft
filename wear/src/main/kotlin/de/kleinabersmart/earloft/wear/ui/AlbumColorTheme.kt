package de.kleinabersmart.earloft.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import com.materialkolor.dynamicColorScheme

/**
 * Wear-Farbschema mit der Akzentfarbe des Albums ([seedColor], einmal aus dem Cover berechnet und in `albums.seedColor`
 * gespeichert, siehe `AlbumColorRepository`). Ohne Farbe bleibt das umgebende Schema.
 */
@Composable
fun AlbumColorTheme(seedColor: Int?, content: @Composable () -> Unit) {
    val base = MaterialTheme.colorScheme
    val scheme = remember(seedColor, base) { if (seedColor == null) base else albumColorScheme(base, seedColor) }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Übernimmt Akzent- und Flächenfarben aus dem dunklen Schema der Grundfarbe; Fehler- und Textfarben bleiben wie in [base]. */
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
