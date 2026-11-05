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
import androidx.compose.runtime.CompositionLocalProvider
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
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.playback.EXTRA_OPEN_NOW_PLAYING
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.wear.ui.AlbumScreen
import keinabersmart.kleinaberlaut.wear.ui.ListCovers
import keinabersmart.kleinaberlaut.wear.ui.LocalAlbumsScreen
import keinabersmart.kleinaberlaut.wear.ui.LocalListCovers
import keinabersmart.kleinaberlaut.wear.ui.LocalArtistsScreen
import keinabersmart.kleinaberlaut.wear.ui.LocalSpokenScreen
import keinabersmart.kleinaberlaut.wear.ui.MusicScreen
import keinabersmart.kleinaberlaut.wear.ui.PhoneMusicScreen
import keinabersmart.kleinaberlaut.wear.ui.RemoteArtistsScreen
import keinabersmart.kleinaberlaut.wear.ui.StartTarget
import keinabersmart.kleinaberlaut.wear.ui.LocalPlaylistsScreen
import keinabersmart.kleinaberlaut.wear.ui.RemoteAlbumsScreen
import keinabersmart.kleinaberlaut.wear.ui.RemoteContinueScreen
import keinabersmart.kleinaberlaut.wear.ui.RemoteNowPlayingScreen
import keinabersmart.kleinaberlaut.wear.ui.RemotePlaylistsScreen
import keinabersmart.kleinaberlaut.wear.ui.RemoteTracksScreen
import keinabersmart.kleinaberlaut.wear.ui.NowPlayingScreen
import keinabersmart.kleinaberlaut.wear.ui.SelectionScreen
import keinabersmart.kleinaberlaut.wear.ui.SleepTimerCustomScreen
import keinabersmart.kleinaberlaut.wear.ui.SleepTimerScreen
import keinabersmart.kleinaberlaut.wear.ui.StartScreen
import keinabersmart.kleinaberlaut.wear.ui.StorageScreen
import kotlinx.serialization.Serializable
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var listCovers: ListCovers

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
            CompositionLocalProvider(LocalListCovers provides listCovers) {
                WearApp(openNowPlaying = openNowPlaying, onNowPlayingOpened = { openNowPlaying = false })
            }
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

/** Wiedergabe-Ansicht der Uhr oder, mit [remote], die des Handys (Fernsteuerung, Wear-Konzept 15). */
@Serializable
data class NowPlayingRoute(val remote: Boolean = false) : NavKey

/** Menü Musik der Uhr: Alben, Künstler, Favoriten, Playlists. */
@Serializable
data object MusicRoute : NavKey

/** Menü Musik des Handys (Wear-Konzept 17), gegliedert wie [MusicRoute]. */
@Serializable
data object PhoneMusicRoute : NavKey

/** Die Künstler der geladenen Alben bzw., mit [remote], alle des Handys. */
@Serializable
data class ArtistsRoute(val remote: Boolean) : NavKey

/** Die (geladenen bzw., mit [remote], alle) Alben eines Künstlers; [name] steht oben. */
@Serializable
data class ArtistRoute(val artistId: Long, val name: String, val remote: Boolean) : NavKey

/** Podcasts ([BookKind.EPISODE]) bzw. Bücher auf der Uhr (Wear-Konzept 16.1/16.2). */
@Serializable
data class SpokenRoute(val kind: BookKind) : NavKey

/** Geladene Alben der Uhr bzw., mit [remote], alle Alben des Handys. */
@Serializable
data class AlbumsRoute(val remote: Boolean) : NavKey

/** Playlists der Uhr bzw., mit [remote], alle Playlists des Handys. */
@Serializable
data class PlaylistsRoute(val remote: Boolean) : NavKey

/** Ein geladenes Album der Uhr. */
@Serializable
data class AlbumRoute(val albumId: Long) : NavKey

/** „Weiterhören“ des Handys: angefangene Bücher und die Warteschlange der Podcasts (Wear-Konzept 16). */
@Serializable
data object PhoneContinueRoute : NavKey

/** Eine Titelliste des Handys: Album, Playlist oder Favoriten ([refId] fehlt bei Favoriten). */
@Serializable
data class RemoteTracksRoute(val kind: WearRemoteListKind, val refId: Long? = null) : NavKey

/** Sleep-Timer wählen (aus der Wiedergabe-Ansicht). */
@Serializable
data object SleepTimerRoute : NavKey

/** Eigene Dauer des Sleep-Timers. */
@Serializable
data object SleepTimerCustomRoute : NavKey

/** Öffnet die Wiedergabe-Ansicht höchstens einmal; sonst stapeln sich Kopien, wenn man mehrfach etwas startet. */
private fun MutableList<NavKey>.openNowPlaying(remote: Boolean = false) {
    val route = NowPlayingRoute(remote)
    if (lastOrNull() != route) add(route)
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
                            onNowPlayingClick = { backStack.openNowPlaying(it) },
                            onClick = { target ->
                                backStack.add(
                                    when (target) {
                                        StartTarget.MUSIC -> MusicRoute
                                        StartTarget.PODCASTS -> SpokenRoute(BookKind.EPISODE)
                                        StartTarget.BOOKS -> SpokenRoute(BookKind.BOOK)
                                        StartTarget.STORAGE -> StorageRoute
                                        StartTarget.PHONE_CONTINUE -> PhoneContinueRoute
                                        StartTarget.PHONE_MUSIC -> PhoneMusicRoute
                                    },
                                )
                            },
                        )
                    }
                    entry<MusicRoute> {
                        MusicScreen(
                            onShuffled = { backStack.openNowPlaying() },
                            onAlbumsClick = { backStack.add(AlbumsRoute(remote = false)) },
                            onArtistsClick = { backStack.add(ArtistsRoute(remote = false)) },
                            onFavoritesClick = { backStack.add(SelectionRoute(it)) },
                            onPlaylistsClick = { backStack.add(PlaylistsRoute(remote = false)) },
                        )
                    }
                    entry<PhoneMusicRoute> {
                        PhoneMusicScreen(
                            onShuffled = { backStack.openNowPlaying(remote = true) },
                            onAlbumsClick = { backStack.add(AlbumsRoute(remote = true)) },
                            onArtistsClick = { backStack.add(ArtistsRoute(remote = true)) },
                            onFavoritesClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.FAVORITES)) },
                            onPlaylistsClick = { backStack.add(PlaylistsRoute(remote = true)) },
                        )
                    }
                    entry<ArtistsRoute> { route ->
                        val onClick: (Long, String) -> Unit = { id, name -> backStack.add(ArtistRoute(id, name, route.remote)) }
                        if (route.remote) RemoteArtistsScreen(onClick = onClick) else LocalArtistsScreen(onClick = onClick)
                    }
                    entry<ArtistRoute> { route ->
                        if (route.remote) {
                            RemoteAlbumsScreen(
                                onClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.ALBUM, it)) },
                                artistId = route.artistId,
                                title = route.name,
                            )
                        } else {
                            LocalAlbumsScreen(onClick = { backStack.add(AlbumRoute(it)) }, artistId = route.artistId, title = route.name)
                        }
                    }
                    entry<SpokenRoute> { route -> LocalSpokenScreen(route.kind, onPlayed = { backStack.openNowPlaying() }) }
                    entry<PhoneContinueRoute> { RemoteContinueScreen(onPlayed = { backStack.openNowPlaying(remote = true) }) }
                    entry<NowPlayingRoute> { route ->
                        if (route.remote) {
                            RemoteNowPlayingScreen()
                        } else {
                            NowPlayingScreen(onOpenSleepTimer = { backStack.add(SleepTimerRoute) })
                        }
                    }
                    entry<AlbumsRoute> { route ->
                        if (route.remote) {
                            RemoteAlbumsScreen(onClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.ALBUM, it)) })
                        } else {
                            LocalAlbumsScreen(onClick = { backStack.add(AlbumRoute(it)) })
                        }
                    }
                    entry<PlaylistsRoute> { route ->
                        if (route.remote) {
                            RemotePlaylistsScreen(onClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.PLAYLIST, it)) })
                        } else {
                            LocalPlaylistsScreen(onClick = { backStack.add(SelectionRoute(it)) })
                        }
                    }
                    entry<AlbumRoute> { route -> AlbumScreen(albumId = route.albumId, onPlayed = { backStack.openNowPlaying() }) }
                    entry<RemoteTracksRoute> { route ->
                        RemoteTracksScreen(route.kind, route.refId, onPlayed = { backStack.openNowPlaying(remote = true) })
                    }
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
                    entry<SelectionRoute> { route -> SelectionScreen(playlistId = route.playlistId, onPlayed = { backStack.openNowPlaying() }) }
                },
            )
        }
    }
}
