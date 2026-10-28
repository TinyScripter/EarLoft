package keinabersmart.kleinaberlaut

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.KleinaberlautApp
import keinabersmart.kleinaberlaut.ui.permission.AudioPermissionGate
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var libraryRefresher: LibraryRefresher

    @Inject lateinit var themeSettings: ThemeSettings

    @Inject lateinit var librarySettings: LibrarySettings

    override fun onStart() {
        super.onStart()
        libraryRefresher.refreshQuick()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val themeMode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = null)
            val useLocalMedia by librarySettings.useLocalMedia.collectAsStateWithLifecycle(initialValue = null)
            // Bis die Einstellungen gelesen sind (wenige Millisekunden): nichts zeichnen statt kurz falsch.
            val mode = themeMode ?: return@setContent
            val localMedia = useLocalMedia ?: return@setContent

            val darkTheme = when (mode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Symbole der Systemleisten passend zur App statt zum System.
            DisposableEffect(darkTheme) {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { darkTheme },
                    navigationBarStyle = SystemBarStyle.auto(LIGHT_SCRIM, DARK_SCRIM) { darkTheme },
                )
                onDispose { }
            }

            KleinaberlautTheme(darkTheme = darkTheme) {
                if (localMedia) {
                    AudioPermissionGate(
                        onGranted = libraryRefresher::refreshNow,
                        onSkip = { lifecycleScope.launch { librarySettings.setUseLocalMedia(false) } },
                    ) {
                        KleinaberlautApp()
                    }
                } else {
                    KleinaberlautApp()
                }
            }
        }
    }

    private companion object {
        // Wie die Standardwerte von enableEdgeToEdge (dort nicht öffentlich).
        val LIGHT_SCRIM = Color.argb(0xe6, 0xFF, 0xFF, 0xFF)
        val DARK_SCRIM = Color.argb(0x80, 0x1b, 0x1b, 0x1b)
    }
}
