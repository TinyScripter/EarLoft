package keinabersmart.kleinaberlaut.wear

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.wear.ambient.AmbientLifecycleObserver
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.playback.EXTRA_OPEN_NOW_PLAYING
import keinabersmart.kleinaberlaut.wear.ui.AddMusicScreen
import keinabersmart.kleinaberlaut.wear.ui.NowPlayingScreen
import keinabersmart.kleinaberlaut.wear.ui.SelectionScreen
import keinabersmart.kleinaberlaut.wear.ui.SleepTimerCustomScreen
import keinabersmart.kleinaberlaut.wear.ui.SleepTimerScreen
import keinabersmart.kleinaberlaut.wear.ui.StartScreen
import keinabersmart.kleinaberlaut.wear.ui.StorageScreen
import kotlinx.serialization.Serializable

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /** Der Tipp auf den Chip der laufenden Musik (Zifferblatt) soll direkt die Wiedergabe-Ansicht öffnen. */
    private var openNowPlaying by mutableStateOf(false)

    /**
     * Ohne eigene Ambient-Ansicht: Beim Display-Timeout geht die App in den Hintergrund und das Zifferblatt erscheint.
     * Die Musik läuft im Dienst weiter, der Chip der laufenden Musik führt zurück zur Wiedergabe-Ansicht.
     * Ohne Beobachter hält Wear OS eine App mit laufender Medien-Benachrichtigung nur abgedunkelt im Vordergrund.
     */
    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            moveTaskToBack(true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))
        requestLocalNetworkAccess()
        // Nur beim frischen Start: Nach einer Neuerstellung (Drehung, Prozess) steht der Intent noch an der Activity.
        if (savedInstanceState == null) openNowPlaying = intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)
        setContent {
            WearApp(openNowPlaying = openNowPlaying, onNowPlayingOpened = { openNowPlaying = false })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false)) openNowPlaying = true
    }

    private val localNetworkRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private fun requestLocalNetworkAccess() {
        if (Build.VERSION.SDK_INT < 37) return
        if (ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED) return
        localNetworkRequest.launch(LOCAL_NETWORK_PERMISSION)
    }
}

@Serializable
data object StartRoute : NavKey

/**
 * Die Uhr lädt direkt vom Server im Heimnetz. Ab Android 17 geht das nur mit `ACCESS_LOCAL_NETWORK`; ohne sie laufen
 * Verbindungen ins LAN in einen Timeout.
 */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Eine Auswahl vom Handy; [playlistId] ist ihre Geräte-Playlist auf der Uhr (Wear-Konzept 7.2). */
@Serializable
data class SelectionRoute(val playlistId: Long) : NavKey

/** Speicher & Downloads (Wear-Konzept 11). */
@Serializable
data object StorageRoute : NavKey

/** Musik hinzufügen: Favoriten und Playlists des Handys (Wear-Konzept 11). */
@Serializable
data object AddMusicRoute : NavKey

@Serializable
data object NowPlayingRoute : NavKey

/** Sleep-Timer wählen (aus der Wiedergabe-Ansicht). */
@Serializable
data object SleepTimerRoute : NavKey

/** Eigene Dauer des Sleep-Timers. */
@Serializable
data object SleepTimerCustomRoute : NavKey

/** Öffnet die Wiedergabe-Ansicht höchstens einmal; sonst stapeln sich Kopien, wenn man mehrfach etwas startet. */
private fun MutableList<NavKey>.openNowPlaying() {
    if (lastOrNull() != NowPlayingRoute) add(NowPlayingRoute)
}

@Composable
fun WearApp(openNowPlaying: Boolean = false, onNowPlayingOpened: () -> Unit = {}) {
    MaterialTheme {
        AppScaffold {
            val backStack = rememberNavBackStack(StartRoute)
            LaunchedEffect(openNowPlaying) {
                if (openNowPlaying) {
                    backStack.openNowPlaying()
                    onNowPlayingOpened()
                }
            }
            NavDisplay(
                backStack = backStack,
                sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<StartRoute> {
                        StartScreen(
                            onSelectionClick = { backStack.add(SelectionRoute(it)) },
                            onStorageClick = { backStack.add(StorageRoute) },
                            onAddMusicClick = { backStack.add(AddMusicRoute) },
                            onNowPlayingClick = { backStack.openNowPlaying() },
                        )
                    }
                    entry<NowPlayingRoute> { NowPlayingScreen(onOpenSleepTimer = { backStack.add(SleepTimerRoute) }) }
                    entry<SleepTimerRoute> {
                        SleepTimerScreen(
                            onCustom = { backStack.add(SleepTimerCustomRoute) },
                            onDone = { backStack.removeLastOrNull() },
                        )
                    }
                    entry<SleepTimerCustomRoute> {
                        // Zurück zur Wiedergabe: erst die eigene Dauer, dann die Auswahl davor schließen.
                        SleepTimerCustomScreen(onDone = { repeat(2) { backStack.removeLastOrNull() } })
                    }
                    entry<StorageRoute> { StorageScreen() }
                    entry<AddMusicRoute> { AddMusicScreen() }
                    entry<SelectionRoute> { route -> SelectionScreen(playlistId = route.playlistId, onPlayed = { backStack.openNowPlaying() }) }
                },
            )
        }
    }
}
