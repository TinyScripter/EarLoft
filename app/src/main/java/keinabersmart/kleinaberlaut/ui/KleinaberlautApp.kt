package keinabersmart.kleinaberlaut.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.core.designsystem.theme.AlbumColorTheme
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import keinabersmart.kleinaberlaut.navigation.AboutRoute
import keinabersmart.kleinaberlaut.navigation.AddAudiobookshelfRoute
import keinabersmart.kleinaberlaut.navigation.BookRoute
import keinabersmart.kleinaberlaut.navigation.PodcastInfoRoute
import keinabersmart.kleinaberlaut.navigation.PodcastQueueRoute
import keinabersmart.kleinaberlaut.navigation.PodcastRoute
import keinabersmart.kleinaberlaut.navigation.PodcastSearchRoute
import keinabersmart.kleinaberlaut.navigation.PodcastSettingsRoute
import keinabersmart.kleinaberlaut.navigation.AddServerRoute
import keinabersmart.kleinaberlaut.navigation.AlbumRoute
import keinabersmart.kleinaberlaut.navigation.ArtistRoute
import keinabersmart.kleinaberlaut.navigation.DownloadsRoute
import keinabersmart.kleinaberlaut.navigation.EditServerRoute
import keinabersmart.kleinaberlaut.navigation.FavoritesRoute
import keinabersmart.kleinaberlaut.navigation.HomeRoute
import keinabersmart.kleinaberlaut.navigation.LibraryRoute
import keinabersmart.kleinaberlaut.navigation.LicensesRoute
import keinabersmart.kleinaberlaut.navigation.ListDetailSceneStrategy
import keinabersmart.kleinaberlaut.navigation.ListDetailSceneStrategy.Companion.detailPane
import keinabersmart.kleinaberlaut.navigation.ListDetailSceneStrategy.Companion.listPane
import keinabersmart.kleinaberlaut.navigation.ListDetailSceneStrategy.Companion.nestedDetailPane
import keinabersmart.kleinaberlaut.navigation.Navigator
import keinabersmart.kleinaberlaut.navigation.BookGroupRoute
import keinabersmart.kleinaberlaut.navigation.NowPlayingRoute
import keinabersmart.kleinaberlaut.navigation.PlaylistRoute
import keinabersmart.kleinaberlaut.navigation.QueueRoute
import keinabersmart.kleinaberlaut.navigation.SearchRoute
import keinabersmart.kleinaberlaut.navigation.SettingsRoute
import keinabersmart.kleinaberlaut.navigation.WatchSettingsRoute
import keinabersmart.kleinaberlaut.navigation.TOP_LEVEL_ROUTES
import keinabersmart.kleinaberlaut.navigation.TopLevelDestination
import keinabersmart.kleinaberlaut.navigation.isFullScreenPlayer
import keinabersmart.kleinaberlaut.navigation.rememberNavigationState
import keinabersmart.kleinaberlaut.ui.components.LocalOfflineController
import keinabersmart.kleinaberlaut.ui.components.LocalWatchController
import keinabersmart.kleinaberlaut.ui.components.rememberOfflineController
import keinabersmart.kleinaberlaut.ui.components.rememberWatchController
import keinabersmart.kleinaberlaut.ui.downloads.DownloadsScreen
import keinabersmart.kleinaberlaut.ui.home.HomeScreen
import keinabersmart.kleinaberlaut.ui.library.AlbumScreen
import keinabersmart.kleinaberlaut.ui.library.ArtistScreen
import keinabersmart.kleinaberlaut.ui.library.FavoritesScreen
import keinabersmart.kleinaberlaut.ui.library.LibraryScreen
import keinabersmart.kleinaberlaut.ui.player.MiniPlayer
import keinabersmart.kleinaberlaut.ui.player.NowPlayingScreen
import keinabersmart.kleinaberlaut.ui.player.PlayerViewModel
import keinabersmart.kleinaberlaut.ui.player.QueueScreen
import keinabersmart.kleinaberlaut.ui.playlists.PlaylistScreen
import keinabersmart.kleinaberlaut.ui.search.SearchScreen
import keinabersmart.kleinaberlaut.ui.settings.AboutScreen
import keinabersmart.kleinaberlaut.ui.settings.LicensesScreen
import keinabersmart.kleinaberlaut.ui.settings.ServerFormScreen
import keinabersmart.kleinaberlaut.ui.settings.SettingsScreen
import keinabersmart.kleinaberlaut.ui.settings.WatchSettingsScreen
import keinabersmart.kleinaberlaut.ui.spoken.AudiobookshelfFormScreen
import keinabersmart.kleinaberlaut.ui.spoken.BookLaunchEffect
import keinabersmart.kleinaberlaut.ui.spoken.BookPlayerScreen
import keinabersmart.kleinaberlaut.ui.spoken.BookPlayerViewModel
import keinabersmart.kleinaberlaut.ui.spoken.BookScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastHomeScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastInfoScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastQueueScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastSearchScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastSettingsScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastLibraryScreen
import keinabersmart.kleinaberlaut.ui.spoken.PodcastScreen
import keinabersmart.kleinaberlaut.ui.spoken.SpokenHomeScreen
import keinabersmart.kleinaberlaut.ui.spoken.SpokenLibraryScreen
import keinabersmart.kleinaberlaut.ui.spoken.SpokenMiniPlayer
import keinabersmart.kleinaberlaut.ui.spoken.BookGroupScreen
import keinabersmart.kleinaberlaut.ui.spoken.SpokenModeHost
import keinabersmart.kleinaberlaut.ui.spoken.LocalBookDownloads
import keinabersmart.kleinaberlaut.ui.spoken.bookFraction
import keinabersmart.kleinaberlaut.ui.spoken.rememberBookDownloads

@Composable
fun KleinaberlautApp(
    openNowPlaying: Boolean = false,
    onNowPlayingOpened: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val navigationState = rememberNavigationState(
        startRoute = HomeRoute,
        topLevelRoutes = TOP_LEVEL_ROUTES,
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }
    LaunchedEffect(openNowPlaying) {
        if (openNowPlaying) {
            navigator.showNowPlaying()
            onNowPlayingOpened()
        }
    }
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()

    // Ab mittlerer Breite (Tablet, Foldable, Handy quer) Navigation Rail statt Bottom-Bar,
    // ab großer Breite zusätzlich Liste und Detail nebeneinander.
    val windowSizeClass = currentWindowAdaptiveInfo().windowSizeClass
    val navigationSuiteType = if (windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_MEDIUM_LOWER_BOUND)) {
        NavigationSuiteType.NavigationRail
    } else {
        NavigationSuiteType.NavigationBar
    }
    val twoPane = windowSizeClass.isWidthAtLeastBreakpoint(WindowSizeClass.WIDTH_DP_EXPANDED_LOWER_BOUND)
    val sceneStrategy = remember(twoPane) { ListDetailSceneStrategy(twoPane) }

    val openAlbum: (Long) -> Unit = { navigator.navigate(AlbumRoute(it)) }
    val openArtist: (Long) -> Unit = { navigator.navigate(ArtistRoute(it)) }
    val openBook: (Long) -> Unit = { navigator.navigate(BookRoute(it)) }
    val openPodcast: (String) -> Unit = { navigator.navigate(PodcastRoute(it)) }
    val setupAudiobookshelf: () -> Unit = { navigator.navigate(AddAudiobookshelfRoute) }
    // Modus gewechselt: die Detailspalte des vorigen Modus schließen (Tab auf seine Startseite zurück).
    val closeDetail: () -> Unit = { navigator.navigate(navigationState.topLevelRoute) }

    val entryProvider = entryProvider {
        entry<HomeRoute> {
            SpokenModeHost(
                music = { HomeScreen(onAlbumClick = openAlbum, onArtistClick = openArtist) },
                podcasts = { PodcastHomeScreen(
                        onEpisodeClick = openBook,
                        onFeedClick = openPodcast,
                        onOpenQueue = { navigator.navigate(PodcastQueueRoute) },
                    ) },
                books = { SpokenHomeScreen(onBookClick = openBook, onSetup = setupAudiobookshelf) },
                onModeChange = closeDetail,
            )
        }
        entry<LibraryRoute>(metadata = listPane()) {
            SpokenModeHost(
                music = {
                    LibraryScreen(
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onFavoritesClick = { navigator.navigate(FavoritesRoute) },
                        onPlaylistClick = { navigator.navigate(PlaylistRoute(it)) },
                    )
                },
                podcasts = { PodcastLibraryScreen(onFeedClick = openPodcast, onEpisodeClick = openBook) },
                books = {
                    SpokenLibraryScreen(
                        onBookClick = openBook,
                        onGroupClick = { kind, name -> navigator.navigate(BookGroupRoute(kind, name)) },
                        onSetup = setupAudiobookshelf,
                    )
                },
                onModeChange = closeDetail,
            )
        }
        entry<BookRoute> { route ->
            // Läuft dieses Buch schon, öffnet „Abspielen“ die Wiedergabe-Ansicht, statt es neu zu starten.
            BookScreen(
                bookId = route.bookId,
                onBack = navigator::goBack,
                isCurrent = playerState.spoken?.bookId == route.bookId,
                onOpenPlayer = navigator::showNowPlaying,
            )
        }
        entry<PodcastRoute>(metadata = detailPane()) { route ->
            PodcastScreen(
                feedId = route.feedId,
                onBack = navigator::goBack,
                onEpisodeClick = openBook,
                onInfo = { navigator.navigate(PodcastInfoRoute(route.feedId)) },
                onSettings = { navigator.navigate(PodcastSettingsRoute(route.feedId)) },
                onSearch = { navigator.navigate(PodcastSearchRoute(route.feedId)) },
            )
        }
        entry<PodcastInfoRoute>(metadata = nestedDetailPane()) { route ->
            PodcastInfoScreen(feedId = route.feedId, onBack = navigator::goBack)
        }
        entry<PodcastSettingsRoute>(metadata = nestedDetailPane()) { route ->
            PodcastSettingsScreen(
                feedId = route.feedId,
                onBack = navigator::goBack,
                // Das Abo ist weg: auch die Detailseite dahinter schließen.
                onUnsubscribed = {
                    navigator.goBack()
                    navigator.goBack()
                },
            )
        }
        entry<BookGroupRoute>(metadata = detailPane()) { route ->
            BookGroupScreen(kind = route.kind, name = route.name, onBack = navigator::goBack, onBookClick = openBook)
        }
        entry<AddAudiobookshelfRoute>(metadata = detailPane()) { AudiobookshelfFormScreen(onDone = navigator::goBack) }
        entry<FavoritesRoute>(metadata = detailPane()) { FavoritesScreen(onBack = navigator::goBack) }
        entry<PlaylistRoute>(metadata = detailPane()) { route ->
            PlaylistScreen(playlistId = route.playlistId, onBack = navigator::goBack)
        }
        entry<SearchRoute> { SearchScreen(onAlbumClick = openAlbum, onArtistClick = openArtist, onBookClick = openBook) }
        entry<PodcastSearchRoute>(metadata = nestedDetailPane()) { route ->
            PodcastSearchScreen(feedId = route.feedId, onBack = navigator::goBack, onEpisodeClick = openBook)
        }
        entry<DownloadsRoute>(metadata = detailPane()) { DownloadsScreen(onBack = navigator::goBack, onAlbumClick = openAlbum, onBookClick = openBook) }
        entry<AlbumRoute>(metadata = nestedDetailPane()) { route ->
            AlbumScreen(albumId = route.albumId, onBack = navigator::goBack)
        }
        entry<ArtistRoute>(metadata = detailPane()) { route ->
            ArtistScreen(artistId = route.artistId, onBack = navigator::goBack, onAlbumClick = openAlbum)
        }
        entry<NowPlayingRoute> {
            // Das laufende Item entscheidet, nicht der Modus: ein Hörbuch hat seine eigene Ansicht.
            if (playerState.spoken != null) {
                BookPlayerScreen(onClose = navigator::goBack)
            } else {
                NowPlayingScreen(onClose = navigator::goBack, onOpenQueue = { navigator.navigate(QueueRoute) })
            }
        }
        entry<QueueRoute> { QueueScreen(onBack = navigator::goBack) }
        entry<PodcastQueueRoute> { PodcastQueueScreen(onBack = navigator::goBack) }
        entry<SettingsRoute>(metadata = listPane()) {
            SettingsScreen(
                onAddServer = { navigator.navigate(AddServerRoute) },
                onEditServer = { navigator.navigate(EditServerRoute(it)) },
                onOpenDownloads = { navigator.navigate(DownloadsRoute) },
                onOpenWatch = { navigator.navigate(WatchSettingsRoute) },
                onAddAudiobookshelf = setupAudiobookshelf,
                onOpenAbout = { navigator.navigate(AboutRoute) },
                onOpenLicenses = { navigator.navigate(LicensesRoute) },
            )
        }
        entry<WatchSettingsRoute>(metadata = detailPane()) { WatchSettingsScreen(onBack = navigator::goBack) }
        entry<AboutRoute>(metadata = detailPane()) { AboutScreen(onBack = navigator::goBack) }
        entry<LicensesRoute>(metadata = detailPane()) { LicensesScreen(onBack = navigator::goBack) }
        entry<AddServerRoute>(metadata = detailPane()) { ServerFormScreen(sourceId = null, onDone = navigator::goBack) }
        entry<EditServerRoute>(metadata = detailPane()) { route ->
            ServerFormScreen(sourceId = route.sourceId, onDone = navigator::goBack)
        }
    }

    val showChrome = !navigator.currentRoute.isFullScreenPlayer
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(showChrome) {
        if (showChrome) navigationSuiteState.show() else navigationSuiteState.hide()
    }

    SyncNoticeEffect()
    BookLaunchEffect()

    CompositionLocalProvider(
        LocalOfflineController provides rememberOfflineController(),
        LocalWatchController provides rememberWatchController(),
        LocalBookDownloads provides rememberBookDownloads(),
    ) {
        // Die Bildschirme kümmern sich selbst um die Statusleiste und – im Vollbild-Player – um die Navigationsleiste.
        NavigationSuiteScaffold(
            navigationItems = {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationSuiteItem(
                        selected = destination.route == navigationState.topLevelRoute,
                        onClick = { navigator.navigate(destination.route) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        navigationSuiteType = navigationSuiteType,
                    )
                }
            },
            navigationSuiteType = navigationSuiteType,
            state = navigationSuiteState,
        ) {
            // Mit Bottom-Bar ist der untere Rand schon verbraucht; mit Rail hält dies Listen und Mini-Player
            // von der Gestenleiste fern.
            val bottomInsets = if (showChrome) WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) else WindowInsets(0)
            Column(Modifier.windowInsetsPadding(bottomInsets)) {
                NavDisplay(
                    entries = navigationState.toDecoratedEntries(entryProvider),
                    sceneStrategies = listOf(sceneStrategy),
                    onBack = { navigator.goBack() },
                    transitionSpec = { fadeThrough() },
                    popTransitionSpec = { fadeThrough() },
                    predictivePopTransitionSpec = { predictiveBack() },
                    modifier = Modifier.weight(1f),
                )
                if (showChrome && playerState.spoken != null) {
                    val bookPlayer: BookPlayerViewModel = hiltViewModel()
                    val bookSeedColor by bookPlayer.seedColor.collectAsStateWithLifecycle()
                    AlbumColorTheme(bookSeedColor) {
                        SpokenMiniPlayer(
                            state = playerState,
                            fractionProvider = bookPlayer::bookFraction,
                            onClick = { navigator.navigate(NowPlayingRoute) },
                            onTogglePlayPause = bookPlayer::togglePlayPause,
                            onSkipBack = bookPlayer::skipBack,
                            onSkipForward = bookPlayer::skipForward,
                        )
                    }
                } else if (showChrome && playerState.hasMedia) {
                    val seedColor by playerViewModel.seedColor.collectAsStateWithLifecycle()
                    AlbumColorTheme(seedColor) {
                        MiniPlayer(
                            state = playerState,
                            positionProvider = { playerViewModel.currentPositionMs },
                            onClick = { navigator.navigate(NowPlayingRoute) },
                            onTogglePlayPause = playerViewModel::togglePlayPause,
                            onNext = playerViewModel::next,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Zurück-Geste: der aktuelle Screen folgt dem Finger und schrumpft bis auf 70 %, der vorherige liegt dahinter.
 * Am Ende der Animation (nach dem Loslassen) blendet der alte Screen aus und der neue steht im Vordergrund.
 */
private fun predictiveBack() = ContentTransform(
    targetContentEnter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.95f),
    initialContentExit = scaleOut(tween(300), targetScale = 0.7f) +
        slideOutHorizontally(tween(300)) { it / 4 } +
        fadeOut(tween(100, delayMillis = 200)),
    targetContentZIndex = -1f,
)

/** Fade-Through (Material): alter Screen blendet kurz aus, der neue blendet verzögert ein und skaliert leicht von 0,92 auf 1. */
private fun fadeThrough() = ContentTransform(
    targetContentEnter = fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f),
    initialContentExit = fadeOut(tween(90)),
)
