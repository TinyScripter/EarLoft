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

/** Whether [EarLoftTheme] is dark – may differ from the system via a setting. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/**
 * App color scheme: from the logo orange ([BrandOrange]) or, with [systemColors] on Android 12+, from the system colors
 * (Material You, derived from the wallpaper).
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
 * The app colors from the logo, without system colors; also used for the widget. "Vibrant" instead of the muted default:
 * accents visibly carry color. The surfaces get their own, weaker orange, otherwise they turn strongly peach- or
 * brown-tinted.
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
 * Recolors [content] based on an album's seed color (ARGB, see `albums.seedColor`).
 * Without a color the surrounding theme stays unchanged.
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
