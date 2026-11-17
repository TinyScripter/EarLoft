package de.kleinabersmart.earloft.wear

import android.Manifest
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
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.playback.EXTRA_OPEN_NOW_PLAYING
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.wear.ui.AlbumScreen
import de.kleinabersmart.earloft.wear.ui.ListCovers
import de.kleinabersmart.earloft.wear.ui.LocalAlbumsScreen
import de.kleinabersmart.earloft.wear.ui.LocalListCovers
import de.kleinabersmart.earloft.wear.ui.LocalArtistsScreen
import de.kleinabersmart.earloft.wear.ui.LocalSpokenScreen
import de.kleinabersmart.earloft.wear.ui.MusicScreen
import de.kleinabersmart.earloft.wear.ui.PhoneFavoritesScreen
import de.kleinabersmart.earloft.wear.ui.PhoneMusicScreen
import de.kleinabersmart.earloft.wear.ui.RemoteArtistsScreen
import de.kleinabersmart.earloft.wear.ui.StartTarget
import de.kleinabersmart.earloft.wear.ui.LocalPlaylistsScreen
import de.kleinabersmart.earloft.wear.ui.RemoteAlbumsScreen
import de.kleinabersmart.earloft.wear.ui.RemoteBooksScreen
import de.kleinabersmart.earloft.wear.ui.RemoteEpisodesScreen
import de.kleinabersmart.earloft.wear.ui.RemotePodcastsScreen
import de.kleinabersmart.earloft.wear.ui.RemoteSpokenScreen
import de.kleinabersmart.earloft.wear.ui.RemoteNowPlayingScreen
import de.kleinabersmart.earloft.wear.ui.RemotePlaylistsScreen
import de.kleinabersmart.earloft.wear.ui.RemoteTracksScreen
import de.kleinabersmart.earloft.wear.ui.NowPlayingScreen
import de.kleinabersmart.earloft.wear.ui.SelectionScreen
import de.kleinabersmart.earloft.wear.ui.ChaptersScreen
import de.kleinabersmart.earloft.wear.ui.RemoteChaptersScreen
import de.kleinabersmart.earloft.wear.ui.SleepTimerCustomScreen
import de.kleinabersmart.earloft.wear.ui.SleepTimerScreen
import de.kleinabersmart.earloft.wear.ui.StartScreen
import de.kleinabersmart.earloft.wear.ui.StorageScreen
import kotlinx.serialization.Serializable
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var listCovers: ListCovers

    /**
     * Bildschirm, den der Start-Intent direkt öffnen soll: die Wiedergabe-Ansicht (Chip der laufenden Musik auf dem
     * Zifferblatt) oder Speicher & Downloads (Symbol der laufenden Downloads, [EXTRA_OPEN_STORAGE]).
     */
    private var openRoute by mutableStateOf<NavKey?>(null)

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
        requestPermissions()
        // Nur beim frischen Start: Nach einer Neuerstellung (Drehung, Prozess) steht der Intent noch an der Activity. Ein Start aus
        // dem Verlauf stellt den alten Intent (z. B. vom Download-Symbol) erneut zu, er gilt nicht noch einmal (Review V19).
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) openRoute = routeOf(intent)
        setContent {
            CompositionLocalProvider(LocalListCovers provides listCovers) {
                WearApp(openRoute = openRoute, onRouteOpened = { openRoute = null })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        routeOf(intent)?.let { openRoute = it }
    }

    private fun routeOf(intent: Intent): NavKey? = when {
        intent.getBooleanExtra(EXTRA_OPEN_NOW_PLAYING, false) -> NowPlayingRoute()
        intent.getBooleanExtra(EXTRA_OPEN_STORAGE, false) -> StorageRoute
        else -> null
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    /** Heimnetz (Downloads vom Server) und Benachrichtigungen (Symbol der laufenden Downloads); das System fragt nacheinander. */
    private fun requestPermissions() {
        val missing = buildList {
            if (Build.VERSION.SDK_INT >= 37) add(LOCAL_NETWORK_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionRequest.launch(missing.toTypedArray())
    }
}

@Serializable
data object StartRoute : NavKey

/** Extra am Start-Intent: direkt Speicher & Downloads öffnen (Tipp auf das Symbol der laufenden Downloads). */
const val EXTRA_OPEN_STORAGE = "de.kleinabersmart.earloft.wear.OPEN_STORAGE"

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

/** Favoriten des Handys mit Alben und Künstlern: Songs, Alben, Künstler. */
@Serializable
data object PhoneFavoritesRoute : NavKey

/** Die Künstler der geladenen Alben bzw., mit [remote], alle des Handys (mit [favorites] die favorisierten). */
@Serializable
data class ArtistsRoute(val remote: Boolean, val favorites: Boolean = false) : NavKey

/** Die (geladenen bzw., mit [remote], alle) Alben eines Künstlers; [name] steht oben. */
@Serializable
data class ArtistRoute(val artistId: Long, val name: String, val remote: Boolean) : NavKey

/** Podcasts ([BookKind.EPISODE]) bzw. Bücher auf der Uhr (Wear-Konzept 16.1/16.2). */
@Serializable
data class SpokenRoute(val kind: BookKind) : NavKey

/** Geladene Alben der Uhr bzw., mit [remote], alle Alben des Handys (mit [favorites] die favorisierten). */
@Serializable
data class AlbumsRoute(val remote: Boolean, val favorites: Boolean = false) : NavKey

/** Playlists der Uhr bzw., mit [remote], alle Playlists des Handys. */
@Serializable
data class PlaylistsRoute(val remote: Boolean) : NavKey

/** Ein geladenes Album der Uhr. */
@Serializable
data class AlbumRoute(val albumId: Long) : NavKey

/** Die Abos des Handys (Wear-Konzept 17). */
@Serializable
data object PhonePodcastsRoute : NavKey

/** Die Folgen eines Abos am Handy; [title] ist der Name des Abos. */
@Serializable
data class PhoneEpisodesRoute(val feedId: String, val title: String) : NavKey

/** Die Hörbücher des Handys. */
@Serializable
data object PhoneBooksRoute : NavKey

/** Eine Folge bzw. ein Buch des Handys: am Handy abspielen, „Auf der Uhr“. */
@Serializable
data class PhoneSpokenRoute(val item: WearRemoteBookDto) : NavKey

/** Eine Titelliste des Handys: Album, Playlist oder Favoriten ([refId] fehlt bei Favoriten). */
@Serializable
data class RemoteTracksRoute(val kind: WearRemoteListKind, val refId: Long? = null) : NavKey

/** Sleep-Timer wählen (aus der Wiedergabe-Ansicht). */
@Serializable
data object SleepTimerRoute : NavKey

/** Eigene Dauer des Sleep-Timers. */
@Serializable
data object SleepTimerCustomRoute : NavKey

/** Kapitel des laufenden Buchs bzw. der Folge (aus der Wiedergabe-Ansicht); mit [remote] die des Handys. */
@Serializable
data class ChaptersRoute(val remote: Boolean = false) : NavKey

/** Öffnet [route] höchstens einmal; sonst stapeln sich Kopien, wenn man mehrfach etwas startet. */
private fun MutableList<NavKey>.openOnce(route: NavKey) {
    if (lastOrNull() != route) add(route)
}

private fun MutableList<NavKey>.openNowPlaying(remote: Boolean = false) = openOnce(NowPlayingRoute(remote))

@Composable
fun WearApp(openRoute: NavKey? = null, onRouteOpened: () -> Unit = {}) {
    MaterialTheme {
        AppScaffold {
            val backStack = rememberNavBackStack(StartRoute)
            LaunchedEffect(openRoute) {
                if (openRoute != null) {
                    backStack.openOnce(openRoute)
                    onRouteOpened()
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
                                        StartTarget.PHONE_MUSIC -> PhoneMusicRoute
                                        StartTarget.PHONE_PODCASTS -> PhonePodcastsRoute
                                        StartTarget.PHONE_BOOKS -> PhoneBooksRoute
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
                            onFavoriteSongsClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.FAVORITES)) },
                            onFavoritesMenuClick = { backStack.add(PhoneFavoritesRoute) },
                            onPlaylistsClick = { backStack.add(PlaylistsRoute(remote = true)) },
                        )
                    }
                    entry<PhoneFavoritesRoute> {
                        PhoneFavoritesScreen(
                            onSongsClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.FAVORITES)) },
                            onAlbumsClick = { backStack.add(AlbumsRoute(remote = true, favorites = true)) },
                            onArtistsClick = { backStack.add(ArtistsRoute(remote = true, favorites = true)) },
                        )
                    }
                    entry<ArtistsRoute> { route ->
                        val onClick: (Long, String) -> Unit = { id, name -> backStack.add(ArtistRoute(id, name, route.remote)) }
                        if (route.remote) RemoteArtistsScreen(onClick = onClick, favorites = route.favorites) else LocalArtistsScreen(onClick = onClick)
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
                    entry<PhonePodcastsRoute> { RemotePodcastsScreen(onClick = { feedId, title -> backStack.add(PhoneEpisodesRoute(feedId, title)) }) }
                    entry<PhoneEpisodesRoute> { route ->
                        RemoteEpisodesScreen(route.feedId, route.title, onClick = { backStack.add(PhoneSpokenRoute(it)) })
                    }
                    entry<PhoneBooksRoute> { RemoteBooksScreen(onClick = { backStack.add(PhoneSpokenRoute(it)) }) }
                    entry<PhoneSpokenRoute> { route -> RemoteSpokenScreen(route.item, onPlayed = { backStack.openNowPlaying(remote = true) }) }
                    entry<NowPlayingRoute> { route ->
                        if (route.remote) {
                            RemoteNowPlayingScreen(onOpenChapters = { backStack.add(ChaptersRoute(remote = true)) })
                        } else {
                            NowPlayingScreen(
                                onOpenSleepTimer = { backStack.add(SleepTimerRoute) },
                                onOpenChapters = { backStack.add(ChaptersRoute()) },
                            )
                        }
                    }
                    entry<AlbumsRoute> { route ->
                        if (route.remote) {
                            RemoteAlbumsScreen(onClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.ALBUM, it)) }, favorites = route.favorites)
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
                    entry<ChaptersRoute> { route ->
                        val onDone: () -> Unit = { backStack.removeLastOrNull() }
                        if (route.remote) RemoteChaptersScreen(onDone) else ChaptersScreen(onDone)
                    }
                    entry<StorageRoute> { StorageScreen() }
                    entry<SelectionRoute> { route -> SelectionScreen(playlistId = route.playlistId, onPlayed = { backStack.openNowPlaying() }) }
                },
            )
        }
    }
}
