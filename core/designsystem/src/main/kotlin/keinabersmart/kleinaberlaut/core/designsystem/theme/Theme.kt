package keinabersmart.kleinaberlaut.core.designsystem.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.materialkolor.rememberDynamicColorScheme

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40

    /* Other default colors to override
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
    */
)

/** Ob [KleinaberlautTheme] dunkel ist – kann per Einstellung vom System abweichen. */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun KleinaberlautTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
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