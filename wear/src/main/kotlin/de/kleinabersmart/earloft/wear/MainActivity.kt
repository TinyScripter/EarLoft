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
import de.kleinabersmart.earloft.wear.ui.RemoteSleepTimerCustomScreen
import de.kleinabersmart.earloft.wear.ui.RemoteSleepTimerScreen
import de.kleinabersmart.earloft.wear.ui.SleepTimerCustomScreen
import de.kleinabersmart.earloft.wear.ui.SleepTimerScreen
import de.kleinabersmart.earloft.wear.ui.StartScreen
import de.kleinabersmart.earloft.wear.ui.StorageScreen
import de.kleinabersmart.earloft.wear.ui.WearTheme
import kotlinx.serialization.Serializable
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var listCovers: ListCovers

    /**
     * Screen the launch intent should open directly: the now playing view (chip of the running music on the
     * watch face) or Storage & downloads (icon of the running downloads, [EXTRA_OPEN_STORAGE]).
     */
    private var openRoute by mutableStateOf<NavKey?>(null)

    /**
     * Without a dedicated ambient view: on display timeout the app goes to the background and the watch face appears.
     * The music keeps playing in the service; the chip of the running music leads back to the now playing view.
     * Without an observer, Wear OS only keeps an app with a running media notification dimmed in the foreground.
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
        // Only on a fresh start: after a recreation (rotation, process) the intent is still on the activity. A start from
        // recents redelivers the old intent (e.g. from the download icon); it does not apply again (Review V19).
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

    /** Home network (downloads from the server) and notifications (icon of running downloads); the system asks one after the other. */
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

/** Extra on the launch intent: open Storage & downloads directly (tap on the icon of the running downloads). */
const val EXTRA_OPEN_STORAGE = "de.kleinabersmart.earloft.wear.OPEN_STORAGE"

/**
 * The watch downloads directly from the server on the home network. From Android 17 on this only works with `ACCESS_LOCAL_NETWORK`; without it
 * connections into the LAN run into a timeout.
 */
private const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** A selection from the phone; [playlistId] is its device playlist on the watch (Wear concept 7.2). */
@Serializable
data class SelectionRoute(val playlistId: Long) : NavKey

/** Storage & downloads (Wear concept 11). */
@Serializable
data object StorageRoute : NavKey

/** Now playing view of the watch or, with [remote], that of the phone (remote control, Wear concept 15). */
@Serializable
data class NowPlayingRoute(val remote: Boolean = false) : NavKey

/** The watch's music menu: albums, artists, favorites, playlists. */
@Serializable
data object MusicRoute : NavKey

/** The phone's music menu (Wear concept 17), structured like [MusicRoute]. */
@Serializable
data object PhoneMusicRoute : NavKey

/** The phone's favorites with albums and artists: songs, albums, artists. */
@Serializable
data object PhoneFavoritesRoute : NavKey

/** The artists of the downloaded albums or, with [remote], all of the phone's (with [favorites] the favorited ones). */
@Serializable
data class ArtistsRoute(val remote: Boolean, val favorites: Boolean = false) : NavKey

/** The (downloaded or, with [remote], all) albums of an artist; [name] is shown at the top. */
@Serializable
data class ArtistRoute(val artistId: Long, val name: String, val remote: Boolean) : NavKey

/** Podcasts ([BookKind.EPISODE]) or books on the watch (Wear concept 16.1/16.2). */
@Serializable
data class SpokenRoute(val kind: BookKind) : NavKey

/** Downloaded albums of the watch or, with [remote], all albums of the phone (with [favorites] the favorited ones). */
@Serializable
data class AlbumsRoute(val remote: Boolean, val favorites: Boolean = false) : NavKey

/** Playlists of the watch or, with [remote], all playlists of the phone. */
@Serializable
data class PlaylistsRoute(val remote: Boolean) : NavKey

/** A downloaded album of the watch. */
@Serializable
data class AlbumRoute(val albumId: Long) : NavKey

/** The phone's subscriptions (Wear concept 17). */
@Serializable
data object PhonePodcastsRoute : NavKey

/** The episodes of a subscription on the phone; [title] is the subscription's name. */
@Serializable
data class PhoneEpisodesRoute(val feedId: String, val title: String) : NavKey

/** The phone's audiobooks. */
@Serializable
data object PhoneBooksRoute : NavKey

/** An episode or book of the phone: play on the phone, "On the watch". */
@Serializable
data class PhoneSpokenRoute(val item: WearRemoteBookDto) : NavKey

/** A track list of the phone: album, playlist or favorites ([refId] is missing for favorites). */
@Serializable
data class RemoteTracksRoute(val kind: WearRemoteListKind, val refId: Long? = null, val selectable: Boolean = true) : NavKey

/** Choose the sleep timer (from the now playing view); with [remote] the phone's. */
@Serializable
data class SleepTimerRoute(val remote: Boolean = false) : NavKey

/** Custom duration of the sleep timer; with [remote] the phone's. */
@Serializable
data class SleepTimerCustomRoute(val remote: Boolean = false) : NavKey

/** Chapters of the current book or episode (from the now playing view); with [remote] the phone's. */
@Serializable
data class ChaptersRoute(val remote: Boolean = false) : NavKey

/** Opens [route] at most once; otherwise copies pile up when starting something several times. */
private fun MutableList<NavKey>.openOnce(route: NavKey) {
    if (lastOrNull() != route) add(route)
}

private fun MutableList<NavKey>.openNowPlaying(remote: Boolean = false) = openOnce(NowPlayingRoute(remote))

/**
 * Closes [route] and then every screen of [alsoClose] directly below it, but only while [route] is still on top: a
 * second tap during the fade-out would otherwise close further screens and could empty the stack. The start screen stays.
 */
private fun MutableList<NavKey>.close(route: NavKey, alsoClose: (NavKey) -> Boolean = { false }) {
    if (lastOrNull() != route) return
    removeAt(lastIndex)
    while (size > 1 && alsoClose(last())) removeAt(lastIndex)
}

@Composable
fun WearApp(openRoute: NavKey? = null, onRouteOpened: () -> Unit = {}) {
    WearTheme {
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
                            // "Download" is in the menu itself: it downloads songs and favorite albums, the songs page only shows the songs.
                            onSongsClick = { backStack.add(RemoteTracksRoute(WearRemoteListKind.FAVORITES, selectable = false)) },
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
                            RemoteNowPlayingScreen(
                                onOpenSleepTimer = { backStack.add(SleepTimerRoute(remote = true)) },
                                onOpenChapters = { backStack.add(ChaptersRoute(remote = true)) },
                            )
                        } else {
                            NowPlayingScreen(
                                onOpenSleepTimer = { backStack.add(SleepTimerRoute()) },
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
                        RemoteTracksScreen(route.kind, route.refId, onPlayed = { backStack.openNowPlaying(remote = true) }, selectable = route.selectable)
                    }
                    entry<SleepTimerRoute> { route ->
                        val onCustom: () -> Unit = { backStack.add(SleepTimerCustomRoute(route.remote)) }
                        val onDone: () -> Unit = { backStack.close(route) }
                        if (route.remote) RemoteSleepTimerScreen(onCustom, onDone) else SleepTimerScreen(onCustom, onDone)
                    }
                    entry<SleepTimerCustomRoute> { route ->
                        // Back to playback: close the custom duration first, then the selection before it.
                        val onDone: () -> Unit = { backStack.close(route) { it is SleepTimerRoute } }
                        if (route.remote) RemoteSleepTimerCustomScreen(onDone) else SleepTimerCustomScreen(onDone)
                    }
                    entry<ChaptersRoute> { route ->
                        val onDone: () -> Unit = { backStack.close(route) }
                        if (route.remote) RemoteChaptersScreen(onDone) else ChaptersScreen(onDone)
                    }
                    entry<StorageRoute> { StorageScreen() }
                    entry<SelectionRoute> { route -> SelectionScreen(playlistId = route.playlistId, onPlayed = { backStack.openNowPlaying() }) }
                },
            )
        }
    }
}
