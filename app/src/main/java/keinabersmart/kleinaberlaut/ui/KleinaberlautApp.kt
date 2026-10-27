package keinabersmart.kleinaberlaut.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import keinabersmart.kleinaberlaut.navigation.AddServerRoute
import keinabersmart.kleinaberlaut.navigation.AlbumRoute
import keinabersmart.kleinaberlaut.navigation.ArtistRoute
import keinabersmart.kleinaberlaut.navigation.DownloadsRoute
import keinabersmart.kleinaberlaut.navigation.HomeRoute
import keinabersmart.kleinaberlaut.navigation.LibraryRoute
import keinabersmart.kleinaberlaut.navigation.Navigator
import keinabersmart.kleinaberlaut.navigation.NowPlayingRoute
import keinabersmart.kleinaberlaut.navigation.QueueRoute
import keinabersmart.kleinaberlaut.navigation.SearchRoute
import keinabersmart.kleinaberlaut.navigation.SettingsRoute
import keinabersmart.kleinaberlaut.navigation.TOP_LEVEL_ROUTES
import keinabersmart.kleinaberlaut.navigation.TopLevelDestination
import keinabersmart.kleinaberlaut.navigation.isFullScreenPlayer
import keinabersmart.kleinaberlaut.navigation.rememberNavigationState
import keinabersmart.kleinaberlaut.ui.downloads.DownloadsScreen
import keinabersmart.kleinaberlaut.ui.home.HomeScreen
import keinabersmart.kleinaberlaut.ui.library.AlbumScreen
import keinabersmart.kleinaberlaut.ui.library.ArtistScreen
import keinabersmart.kleinaberlaut.ui.library.LibraryScreen
import keinabersmart.kleinaberlaut.ui.player.MiniPlayer
import keinabersmart.kleinaberlaut.ui.player.NowPlayingScreen
import keinabersmart.kleinaberlaut.ui.player.PlayerViewModel
import keinabersmart.kleinaberlaut.ui.player.QueueScreen
import keinabersmart.kleinaberlaut.ui.search.SearchScreen
import keinabersmart.kleinaberlaut.ui.settings.AddServerScreen
import keinabersmart.kleinaberlaut.ui.settings.SettingsScreen

@Composable
fun KleinaberlautApp(
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val navigationState = rememberNavigationState(
        startRoute = HomeRoute,
        topLevelRoutes = TOP_LEVEL_ROUTES,
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    val openAlbum: (Long) -> Unit = { navigator.navigate(AlbumRoute(it)) }
    val openArtist: (Long) -> Unit = { navigator.navigate(ArtistRoute(it)) }

    val entryProvider = entryProvider {
        entry<HomeRoute> { HomeScreen(onAlbumClick = openAlbum, onOpenSettings = { navigator.navigate(SettingsRoute) }) }
        entry<LibraryRoute> { LibraryScreen(onAlbumClick = openAlbum, onArtistClick = openArtist) }
        entry<SearchRoute> { SearchScreen(onAlbumClick = openAlbum, onArtistClick = openArtist) }
        entry<DownloadsRoute> { DownloadsScreen(onAlbumClick = openAlbum) }
        entry<AlbumRoute> { route -> AlbumScreen(albumId = route.albumId, onBack = navigator::goBack) }
        entry<ArtistRoute> { route ->
            ArtistScreen(artistId = route.artistId, onBack = navigator::goBack, onAlbumClick = openAlbum)
        }
        entry<NowPlayingRoute> {
            NowPlayingScreen(onClose = navigator::goBack, onOpenQueue = { navigator.navigate(QueueRoute) })
        }
        entry<QueueRoute> { QueueScreen(onBack = navigator::goBack) }
        entry<SettingsRoute> {
            SettingsScreen(onBack = navigator::goBack, onAddServer = { navigator.navigate(AddServerRoute) })
        }
        entry<AddServerRoute> { AddServerScreen(onDone = navigator::goBack) }
    }

    val showChrome = !navigator.currentRoute.isFullScreenPlayer

    Scaffold(
        // Die Bildschirme kümmern sich selbst um Statusleiste und – ohne Bottom-Bar – die Navigationsleiste.
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (showChrome) {
                Column {
                    if (playerState.hasMedia) {
                        MiniPlayer(
                            state = playerState,
                            positionProvider = { playerViewModel.currentPositionMs },
                            onClick = { navigator.navigate(NowPlayingRoute) },
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onNext = playerViewModel::next,
                        )
                    }
                    NavigationBar {
                        TopLevelDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                selected = destination.route == navigationState.topLevelRoute,
                                onClick = { navigator.navigate(destination.route) },
                                icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                                label = { Text(stringResource(destination.label)) },
                            )
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryProvider),
            onBack = { navigator.goBack() },
            modifier = Modifier.padding(innerPadding),
        )
    }
}
