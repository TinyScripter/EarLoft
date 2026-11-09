package de.kleinabersmart.earloft.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamicColorScheme
import com.materialkolor.rememberDynamicColorScheme

/** Ob [EarLoftTheme] dunkel ist – kann per Einstellung vom System abweichen. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Farbschema der App: aus dem Logo-Orange ([BrandOrange]) oder, mit [systemColors] ab Android 12, aus den Systemfarben
 * (Material You, vom Hintergrundbild abgeleitet).
 */
@Composable
fun EarLoftTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    systemColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        remember(darkTheme) { appColorScheme(darkTheme) }
    }

    CompositionLocalProvider(LocalDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

/**
 * Die App-Farben aus dem Logo, ohne Systemfarben; auch für das Widget. „Vibrant“ statt des gedeckten Standards: Akzente
 * tragen sichtbar Farbe. Die Flächen bekommen ein eigenes, schwächeres Orange, sonst werden sie kräftig pfirsich- bzw.
 * braunstichig.
 */
fun appColorScheme(isDark: Boolean): ColorScheme = dynamicColorScheme(
    seedColor = BrandOrange,
    isDark = isDark,
    isAmoled = false,
    neutral = BrandOrangeMuted,
    neutralVariant = BrandOrangeMuted,
    style = PaletteStyle.Vibrant,
)

/**
 * Färbt [content] nach der Grundfarbe eines Albums (ARGB, siehe `albums.seedColor`) um.
 * Ohne Farbe bleibt das umgebende Theme unverändert.
 */
@Composable
fun AlbumColorTheme(
    seedColor: Int?,
    darkTheme: Boolean = LocalDarkTheme.current,
    content: @Composable () -> Unit
) {
    if (seedColor == null) {
        content()
        return
    }
    MaterialTheme(
        colorScheme = rememberDynamicColorScheme(seedColor = Color(seedColor), isDark = darkTheme, isAmoled = false),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
        content = content
    )
}
