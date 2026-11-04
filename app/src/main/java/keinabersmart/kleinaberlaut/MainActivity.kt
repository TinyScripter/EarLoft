package keinabersmart.kleinaberlaut

import android.app.SearchManager
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.MediaStore
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.cast.CastVolumeIndicator
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibraryRefresher
import keinabersmart.kleinaberlaut.core.data.sync.LibrarySettings
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.playback.EXTRA_OPEN_NOW_PLAYING
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.KleinaberlautApp
import keinabersmart.kleinaberlaut.ui.permission.AudioPermissionGate
import keinabersmart.kleinaberlaut.ui.theme.ThemeMode
import keinabersmart.kleinaberlaut.ui.theme.ThemeSettings
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var libraryRefresher: LibraryRefresher
    @Inject lateinit var spokenRefresher: SpokenRefresher
    @Inject lateinit var podcastRefresher: PodcastRefresher

    @Inject lateinit var themeSettings: ThemeSettings

    @Inject lateinit var librarySettings: LibrarySettings

    @Inject lateinit var playbackConnection: PlaybackConnection

    /** Der Tipp auf die Benachrichtigung der laufenden Musik soll immer die Wiedergabe-Ansicht zeigen. */
    private var openNowPlaying by mutableStateOf(false)

    /** Zählt die abgefangenen Lautstärketasten beim Casten; jede Änderung blendet [CastVolumeIndicator] ein. */
    private var castVolumePresses by mutableIntStateOf(0)

    /**
     * Beim Casten ändern die Lautstärketasten die Lautstärke des Cast-Geräts und die App zeigt sie selbst an: Die
     * System-Oberfläche des Pixels zeigt ihren Dialog für Cast-Sitzungen nur sporadisch. Im Hintergrund bleibt es beim
     * System. Den aktuellen [PlaybackConnection.state] hält die App-Oberfläche verbunden, solange sie sichtbar ist.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isCastVolumeKey(keyCode)) return super.onKeyDown(keyCode, event)
        playbackConnection.changeRemoteVolume(up = keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        castVolumePresses++
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        isCastVolumeKey(keyCode) || super.onKeyUp(keyCode, event)

    private fun isCastVolumeKey(keyCode: Int): Boolean =
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) && playbackConnection.state.value.isRemote

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying = true
        playFromSearch(intent)
    }

    /** Sprachsuche des Systems („Spiele … mit kleinaberlaut“): abspielen und die Wiedergabe-Ansicht zeigen. */
    private fun playFromSearch(intent: Intent) {
        if (intent.action != MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) return
        playbackConnection.playFromSearch(intent.getStringExtra(SearchManager.QUERY).orEmpty())
        openNowPlaying = true
    }

    /** Abgleiche beim Öffnen der App; der bloße Start des Prozesses (Hintergrund-Jobs, Uhr) gleicht nichts ab. */
    override fun onStart() {
        super.onStart()
        libraryRefresher.refreshQuick()
        spokenRefresher.refreshQuick()
        podcastRefresher.refreshStale()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Nur beim frischen Start: Nach einer Neuerstellung (Drehung, Prozess) steht der Intent noch an der Activity.
        if (savedInstanceState == null) {
            openNowPlaying = intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)
            playFromSearch(intent)
        }
        setContent {
            val themeMode by themeSettings.mode.collectAsStateWithLifecycle(initialValue = null)
            val systemColorsSetting by themeSettings.systemColors.collectAsStateWithLifecycle(initialValue = null)
            val useLocalMedia by librarySettings.useLocalMedia.collectAsStateWithLifecycle(initialValue = null)
            // Bis die Einstellungen gelesen sind (wenige Millisekunden): nichts zeichnen statt kurz falsch.
            val mode = themeMode ?: return@setContent
            val systemColors = systemColorsSetting ?: return@setContent
            val localMedia = useLocalMedia ?: return@setContent
            val alwaysNavigationBar by themeSettings.alwaysShowNavigationBar.collectAsStateWithLifecycle(initialValue = true)

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

            KleinaberlautTheme(darkTheme = darkTheme, systemColors = systemColors) {
                Box(Modifier.fillMaxSize()) {
                    AudioPermissionGate(
                        enabled = localMedia,
                        onGranted = libraryRefresher::refreshNow,
                        onSkip = { lifecycleScope.launch { librarySettings.setUseLocalMedia(false) } },
                    ) {
                        KleinaberlautApp(
                            openNowPlaying = openNowPlaying,
                            onNowPlayingOpened = { openNowPlaying = false },
                            alwaysShowNavigationBar = alwaysNavigationBar,
                        )
                    }
                    CastVolumeIndicator(
                        presses = castVolumePresses,
                        state = playbackConnection.state,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .windowInsetsPadding(WindowInsets.statusBars)
                            .padding(16.dp),
                    )
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
