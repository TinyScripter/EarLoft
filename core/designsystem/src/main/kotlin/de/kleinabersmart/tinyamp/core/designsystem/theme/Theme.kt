package de.kleinabersmart.tinyamp.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.PaletteStyle
import com.materialkolor.rememberDynamicColorScheme

/** Ob [TinyAmpTheme] dunkel ist – kann per Einstellung vom System abweichen. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * Farbschema der App: aus dem Logo-Violett ([BrandViolet]) oder, mit [systemColors] ab Android 12, aus den Systemfarben
 * (Material You, vom Hintergrundbild abgeleitet).
 */
@Composable
fun TinyAmpTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    systemColors: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = if (systemColors && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        // „Vibrant“ statt des gedeckten Standards: Akzente tragen sichtbar Farbe, die Flächen bleiben ruhig.
        rememberDynamicColorScheme(seedColor = BrandViolet, isDark = darkTheme, isAmoled = false, style = PaletteStyle.Vibrant)
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
