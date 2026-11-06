package de.kleinabersmart.tinyamp.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SharedTransitionLayout
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
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItem
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.material3.adaptive.navigationsuite.rememberNavigationSuiteScaffoldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.core.designsystem.theme.AlbumColorTheme
import de.kleinabersmart.tinyamp.core.playback.PlayerState
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import de.kleinabersmart.tinyamp.navigation.AboutRoute
import de.kleinabersmart.tinyamp.ui.snackbar.SnackbarMessage
import de.kleinabersmart.tinyamp.navigation.AddAudiobookshelfRoute
import de.kleinabersmart.tinyamp.navigation.BookRoute
import de.kleinabersmart.tinyamp.navigation.PodcastInfoRoute
import de.kleinabersmart.tinyamp.navigation.PodcastQueueRoute
import de.kleinabersmart.tinyamp.navigation.PodcastQueueSearchRoute
import de.kleinabersmart.tinyamp.navigation.PodcastRoute
import de.kleinabersmart.tinyamp.navigation.PodcastSearchRoute
import de.kleinabersmart.tinyamp.navigation.PodcastSettingsRoute
import de.kleinabersmart.tinyamp.navigation.AddPodcastRoute
import de.kleinabersmart.tinyamp.navigation.PodcastPreviewRoute
import de.kleinabersmart.tinyamp.ui.spoken.AddPodcastScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastPreviewScreen
import de.kleinabersmart.tinyamp.navigation.AddServerRoute
import de.kleinabersmart.tinyamp.navigation.AlbumRoute
import de.kleinabersmart.tinyamp.navigation.ArtistRoute
import de.kleinabersmart.tinyamp.navigation.DownloadsRoute
import de.kleinabersmart.tinyamp.navigation.EditAudiobookshelfRoute
import de.kleinabersmart.tinyamp.navigation.EditServerRoute
import de.kleinabersmart.tinyamp.navigation.FavoritesRoute
import de.kleinabersmart.tinyamp.navigation.HomeRoute
import de.kleinabersmart.tinyamp.navigation.LibraryRoute
import de.kleinabersmart.tinyamp.navigation.LicensesRoute
import de.kleinabersmart.tinyamp.navigation.LocalSharedTransitionScope
import de.kleinabersmart.tinyamp.navigation.ListDetailSceneStrategy
import de.kleinabersmart.tinyamp.navigation.ListDetailSceneStrategy.Companion.detailPane
import de.kleinabersmart.tinyamp.navigation.ListDetailSceneStrategy.Companion.listPane
import de.kleinabersmart.tinyamp.navigation.ListDetailSceneStrategy.Companion.nestedDetailPane
import de.kleinabersmart.tinyamp.navigation.Navigator
import de.kleinabersmart.tinyamp.navigation.BookGroupKind
import de.kleinabersmart.tinyamp.navigation.BookGroupRoute
import de.kleinabersmart.tinyamp.navigation.NowPlayingRoute
import de.kleinabersmart.tinyamp.navigation.PlaylistRoute
import de.kleinabersmart.tinyamp.navigation.QueueRoute
import de.kleinabersmart.tinyamp.navigation.SearchRoute
import de.kleinabersmart.tinyamp.navigation.SettingsAudiobooksRoute
import de.kleinabersmart.tinyamp.navigation.SettingsGeneralRoute
import de.kleinabersmart.tinyamp.navigation.SettingsMusicRoute
import de.kleinabersmart.tinyamp.navigation.SettingsPodcastsRoute
import de.kleinabersmart.tinyamp.navigation.SettingsRoute
import de.kleinabersmart.tinyamp.navigation.WatchSettingsRoute
import de.kleinabersmart.tinyamp.navigation.TOP_LEVEL_ROUTES
import de.kleinabersmart.tinyamp.navigation.TopLevelDestination
import de.kleinabersmart.tinyamp.navigation.isFullScreenPlayer
import de.kleinabersmart.tinyamp.navigation.rememberNavigationState
import de.kleinabersmart.tinyamp.ui.components.LocalOfflineController
import de.kleinabersmart.tinyamp.ui.components.LocalWatchController
import de.kleinabersmart.tinyamp.ui.components.rememberOfflineController
import de.kleinabersmart.tinyamp.ui.components.rememberWatchController
import de.kleinabersmart.tinyamp.ui.downloads.DownloadsScreen
import de.kleinabersmart.tinyamp.ui.home.HomeScreen
import de.kleinabersmart.tinyamp.ui.library.AlbumScreen
import de.kleinabersmart.tinyamp.ui.library.ArtistScreen
import de.kleinabersmart.tinyamp.ui.library.FavoritesScreen
import de.kleinabersmart.tinyamp.ui.library.LibraryScreen
import de.kleinabersmart.tinyamp.ui.player.MiniPlayer
import de.kleinabersmart.tinyamp.ui.player.NowPlayingScreen
import de.kleinabersmart.tinyamp.ui.player.PlayerViewModel
import de.kleinabersmart.tinyamp.ui.player.QueueScreen
import de.kleinabersmart.tinyamp.ui.playlists.PlaylistScreen
import de.kleinabersmart.tinyamp.ui.search.SearchScreen
import de.kleinabersmart.tinyamp.ui.settings.AboutScreen
import de.kleinabersmart.tinyamp.ui.settings.LicensesScreen
import de.kleinabersmart.tinyamp.ui.settings.AudiobookSettingsScreen
import de.kleinabersmart.tinyamp.ui.settings.GeneralSettingsScreen
import de.kleinabersmart.tinyamp.ui.settings.MusicSettingsScreen
import de.kleinabersmart.tinyamp.ui.settings.PodcastsSettingsScreen
import de.kleinabersmart.tinyamp.ui.settings.ServerFormScreen
import de.kleinabersmart.tinyamp.ui.settings.SettingsScreen
import de.kleinabersmart.tinyamp.ui.settings.WatchSettingsScreen
import de.kleinabersmart.tinyamp.ui.snackbar.LocalSnackbars
import de.kleinabersmart.tinyamp.ui.snackbar.SnackbarViewModel
import de.kleinabersmart.tinyamp.ui.spoken.AudiobookshelfFormScreen
import de.kleinabersmart.tinyamp.ui.spoken.BookLaunchEffect
import de.kleinabersmart.tinyamp.ui.spoken.BookPlayerScreen
import de.kleinabersmart.tinyamp.ui.spoken.BookPlayerViewModel
import de.kleinabersmart.tinyamp.ui.spoken.BookScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastHomeScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastInfoScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastQueueScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastQueueSearchScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastSearchScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastSettingsScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastLibraryScreen
import de.kleinabersmart.tinyamp.ui.spoken.PodcastScreen
import de.kleinabersmart.tinyamp.ui.spoken.SpokenHomeScreen
import de.kleinabersmart.tinyamp.ui.spoken.SpokenLibraryScreen
import de.kleinabersmart.tinyamp.ui.spoken.SpokenMiniPlayer
import de.kleinabersmart.tinyamp.ui.spoken.BookGroupScreen
import de.kleinabersmart.tinyamp.ui.spoken.SpokenModeHost
import de.kleinabersmart.tinyamp.ui.spoken.LocalBookDownloads
import de.kleinabersmart.tinyamp.ui.spoken.LocalFeedDownloads
import de.kleinabersmart.tinyamp.ui.spoken.bookFraction
import de.kleinabersmart.tinyamp.ui.spoken.rememberBookDownloads
import de.kleinabersmart.tinyamp.ui.spoken.rememberFeedDownloads
import kotlinx.coroutines.launch

@Composable
fun TinyAmpApp(
    openNowPlaying: Boolean = false,
    onNowPlayingOpened: () -> Unit = {},
    alwaysShowNavigationBar: Boolean = true,
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
    // Hier nur ableiten: Jede Änderung am Player (Play/Pause, Titel, Warteschlange, Tempo) setzte sonst Navigation und
    // Scaffold neu zusammen. Den ganzen Zustand liest erst der Mini-Player.
    val playerState = playerViewModel.state.collectAsStateWithLifecycle()
    val playsSpoken by remember { derivedStateOf { playerState.value.spoken != null } }

    // Ab mittlerer Breite (Tablet, Foldable, Handy quer) Navigation Rail statt Bottom-Bar,
    // ab großer Breite zusätzlich Liste und Detail nebeneinander.
    val windowSizeClass = currentWindowAdaptiveInfoV2().windowSizeClass
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
    val openPodcastSettings: (String) -> Unit = { navigator.navigate(PodcastSettingsRoute(it)) }
    val openAddPodcast: () -> Unit = { navigator.navigate(AddPodcastRoute()) }
    val setupAudiobookshelf: () -> Unit = { navigator.navigate(AddAudiobookshelfRoute) }
    // Modus gewechselt: die Detailspalte des vorigen Modus schließen (Tab auf seine Startseite zurück).
    val closeDetail: () -> Unit = { navigator.navigate(navigationState.topLevelRoute) }
    val openSearch: () -> Unit = { navigator.navigate(SearchRoute()) }
    val addMusicServer: () -> Unit = { navigator.navigate(AddServerRoute) }
    // Aus einem leeren Bereich direkt zu den Schaltern der Bereiche (Einstellungen → Allgemein).
    val openAreaSettings: () -> Unit = {
        navigator.navigate(SettingsRoute)
        navigator.navigate(SettingsGeneralRoute)
    }

    val entryProvider = entryProvider {
        entry<HomeRoute> {
            SpokenModeHost(
                music = {
                    HomeScreen(
                        onAlbumClick = openAlbum,
                        onArtistClick = openArtist,
                        onOpenQueue = { navigator.navigate(QueueRoute(overPlayer = false)) },
                        onAddServer = addMusicServer,
                    )
                },
                podcasts = { PodcastHomeScreen(
                        onEpisodeClick = openBook,
                        onFeedClick = openPodcast,
                        onFeedSettings = openPodcastSettings,
                        onOpenQueue = { navigator.navigate(PodcastQueueRoute) },
                        onAddPodcast = openAddPodcast,
                    ) },
                books = { SpokenHomeScreen(onBookClick = openBook, onSetup = setupAudiobookshelf) },
                onModeChange = closeDetail,
                onSearch = openSearch,
                onOpenAreaSettings = openAreaSettings,
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
                        onAddServer = addMusicServer,
                    )
                },
                podcasts = {
                    PodcastLibraryScreen(
                        onFeedClick = openPodcast,
                        onFeedSettings = openPodcastSettings,
                        onEpisodeClick = openBook,
                        onAddPodcast = openAddPodcast,
                    )
                },
                books = {
                    SpokenLibraryScreen(
                        onBookClick = openBook,
                        onGroupClick = { kind, name -> navigator.navigate(BookGroupRoute(kind, name)) },
                        onSetup = setupAudiobookshelf,
                    )
                },
                onModeChange = closeDetail,
                onSearch = openSearch,
                onOpenAreaSettings = openAreaSettings,
            )
        }
        entry<BookRoute> { route ->
            // „Abspielen“ startet das Buch bzw. setzt es fort und öffnet danach die Wiedergabe-Ansicht.
            BookScreen(
                bookId = route.bookId,
                onBack = navigator::goBack,
                onOpenPlayer = navigator::showNowPlaying,
                onOpenFeed = navigator::showFeed,
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
        entry<AddPodcastRoute>(metadata = detailPane()) {
            AddPodcastScreen(
                onBack = navigator::goBack,
                // Das neue Abo tritt an die Stelle der Seite, wie früher nach dem Dialog.
                onFeedAdded = { navigator.replaceTop(PodcastRoute(it)) },
                onOpenFeed = openPodcast,
                onPreview = { navigator.navigate(PodcastPreviewRoute(it.feedUrl, it.title, it.author, it.imageUrl)) },
                onOpenSettings = { navigator.navigate(SettingsPodcastsRoute) },
            )
        }
        entry<PodcastPreviewRoute>(metadata = nestedDetailPane()) { route ->
            // Die Vorschau lädt nur und macht dann der Seite des (Probe-)Abos Platz; Zurück führt zu den Treffern.
            PodcastPreviewScreen(
                feedUrl = route.feedUrl,
                title = route.title,
                author = route.author,
                imageUrl = route.imageUrl,
                onBack = navigator::goBack,
                onOpened = { navigator.replaceTop(PodcastRoute(it)) },
            )
        }
        entry<PodcastInfoRoute>(metadata = nestedDetailPane()) { route ->
            PodcastInfoScreen(feedId = route.feedId, onBack = navigator::goBack)
        }
        entry<PodcastSettingsRoute>(metadata = nestedDetailPane()) { route ->
            PodcastSettingsScreen(
                feedId = route.feedId,
                onBack = navigator::goBack,
                // Das Abo ist weg: alle seine Seiten schließen (die Einstellungen kommen auch direkt aus dem Langklick-Menü).
                onUnsubscribed = { navigator.closeFeed(route.feedId) },
            )
        }
        entry<BookGroupRoute>(metadata = detailPane()) { route ->
            BookGroupScreen(kind = route.kind, name = route.name, onBack = navigator::goBack, onBookClick = openBook)
        }
        entry<AddAudiobookshelfRoute>(metadata = detailPane()) { AudiobookshelfFormScreen(serverId = null, onDone = navigator::goBack) }
        entry<EditAudiobookshelfRoute>(metadata = detailPane()) { route ->
            AudiobookshelfFormScreen(serverId = route.serverId, onDone = navigator::goBack)
        }
        entry<FavoritesRoute>(metadata = detailPane()) { FavoritesScreen(onBack = navigator::goBack) }
        entry<PlaylistRoute>(metadata = detailPane()) { route ->
            PlaylistScreen(playlistId = route.playlistId, onBack = navigator::goBack)
        }
        entry<SearchRoute> {
            // Der Bereich von Start bzw. Bibliothek bestimmt, wo gesucht wird.
            SearchScreen(
                onBack = navigator::goBack,
                onAlbumClick = openAlbum,
                onArtistClick = openArtist,
                onBookClick = openBook,
                onFeedClick = openPodcast,
                onFeedSettings = openPodcastSettings,
                onAuthorClick = { navigator.navigate(BookGroupRoute(BookGroupKind.AUTHOR, it)) },
                onSeriesClick = { navigator.navigate(BookGroupRoute(BookGroupKind.SERIES, it)) },
            )
        }
        entry<PodcastSearchRoute>(metadata = nestedDetailPane()) { route ->
            PodcastSearchScreen(feedId = route.feedId, onBack = navigator::goBack, onEpisodeClick = openBook)
        }
        entry<DownloadsRoute>(metadata = detailPane()) { DownloadsScreen(
                onBack = navigator::goBack,
                onAlbumClick = openAlbum,
                onBookClick = openBook,
                onPodcastClick = openPodcast,
            ) }
        entry<AlbumRoute>(metadata = nestedDetailPane()) { route ->
            AlbumScreen(albumId = route.albumId, onBack = navigator::goBack)
        }
        entry<ArtistRoute>(metadata = detailPane()) { route ->
            ArtistScreen(artistId = route.artistId, onBack = navigator::goBack, onAlbumClick = openAlbum)
        }
        entry<NowPlayingRoute> {
            // Das laufende Item entscheidet, nicht der Modus: ein Hörbuch hat seine eigene Ansicht.
            if (playsSpoken) {
                BookPlayerScreen(onClose = navigator::goBack, onEpisodeClick = openBook)
            } else {
                NowPlayingScreen(onClose = navigator::goBack, onOpenQueue = { navigator.navigate(QueueRoute(overPlayer = true)) })
            }
        }
        entry<QueueRoute> { QueueScreen(onBack = navigator::goBack) }
        entry<PodcastQueueRoute> {
            PodcastQueueScreen(
                onBack = navigator::goBack,
                onSearch = { navigator.navigate(PodcastQueueSearchRoute) },
                onEpisodeClick = openBook,
            )
        }
        entry<PodcastQueueSearchRoute> { PodcastQueueSearchScreen(onBack = navigator::goBack, onEpisodeClick = openBook) }
        entry<SettingsRoute>(metadata = listPane()) {
            SettingsScreen(
                onOpenGeneral = { navigator.navigate(SettingsGeneralRoute) },
                onOpenMusic = { navigator.navigate(SettingsMusicRoute) },
                onOpenPodcasts = { navigator.navigate(SettingsPodcastsRoute) },
                onOpenAudiobooks = { navigator.navigate(SettingsAudiobooksRoute) },
                onOpenWatch = { navigator.navigate(WatchSettingsRoute) },
                onOpenAbout = { navigator.navigate(AboutRoute) },
            )
        }
        entry<SettingsGeneralRoute>(metadata = detailPane()) {
            GeneralSettingsScreen(onBack = navigator::goBack, onOpenDownloads = { navigator.navigate(DownloadsRoute) })
        }
        entry<SettingsMusicRoute>(metadata = detailPane()) {
            MusicSettingsScreen(
                onBack = navigator::goBack,
                onAddServer = { navigator.navigate(AddServerRoute) },
                onEditServer = { navigator.navigate(EditServerRoute(it)) },
            )
        }
        entry<SettingsPodcastsRoute>(metadata = detailPane()) { PodcastsSettingsScreen(onBack = navigator::goBack) }
        entry<SettingsAudiobooksRoute>(metadata = detailPane()) {
            AudiobookSettingsScreen(
                onBack = navigator::goBack,
                onAddServer = setupAudiobookshelf,
                onEditServer = { navigator.navigate(EditAudiobookshelfRoute(it)) },
            )
        }
        entry<WatchSettingsRoute>(metadata = detailPane()) { WatchSettingsScreen(onBack = navigator::goBack) }
        entry<AboutRoute>(metadata = detailPane()) { AboutScreen(onBack = navigator::goBack, onOpenLicenses = { navigator.navigate(LicensesRoute) }) }
        entry<LicensesRoute>(metadata = detailPane()) { LicensesScreen(onBack = navigator::goBack) }
        entry<AddServerRoute>(metadata = detailPane()) { ServerFormScreen(sourceId = null, onDone = navigator::goBack) }
        entry<EditServerRoute>(metadata = detailPane()) { route ->
            ServerFormScreen(sourceId = route.sourceId, onDone = navigator::goBack)
        }
    }

    // Navigationsleiste (unten bzw. als Rail links) bleibt auf Wunsch auch im Player sichtbar; der Mini-Player entfällt dort immer.
    val showMiniPlayer = !navigator.currentRoute.isFullScreenPlayer
    val showNavigation = alwaysShowNavigationBar || showMiniPlayer
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(showNavigation) {
        if (showNavigation) navigationSuiteState.show() else navigationSuiteState.hide()
    }

    val snackbars = hiltViewModel<SnackbarViewModel>().snackbars
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(snackbars) {
        snackbars.messages.collect { message ->
            // Ein neuer Hinweis ersetzt den sichtbaren (etwa „Wird aktualisiert“ durch das Ergebnis), außer der soll bleiben;
            // dann wartet der neue dahinter.
            val visible = snackbarHost.currentSnackbarData
            if ((visible?.visuals as? AppSnackbarVisuals)?.keep != true) visible?.dismiss()
            launch {
                val result = snackbarHost.showSnackbar(AppSnackbarVisuals(message))
                if (result == SnackbarResult.ActionPerformed) message.onAction?.invoke()
            }
        }
    }

    CompositionLocalProvider(
        LocalSnackbars provides snackbars,
        LocalOfflineController provides rememberOfflineController(),
        LocalWatchController provides rememberWatchController(),
        LocalBookDownloads provides rememberBookDownloads(),
        LocalFeedDownloads provides rememberFeedDownloads(),
    ) {
        SyncNoticeEffect()
        RefreshEventEffect()
        RestoreNoticeEffect(
            onEnter = { missing ->
                navigator.navigate(SettingsRoute)
                when {
                    missing.audiobookServers == 0 -> navigator.navigate(SettingsMusicRoute)
                    missing.musicServers == 0 -> navigator.navigate(SettingsAudiobooksRoute)
                }
            },
        )
        BookLaunchEffect()
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
            val bottomInsets = if (showNavigation) WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) else WindowInsets(0)
            Column(Modifier.windowInsetsPadding(bottomInsets)) {
                // Gemeinsame Elemente (der Kopf von Start und Bibliothek) bleiben beim Seitenwechsel stehen.
                SharedTransitionLayout(Modifier.weight(1f)) {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        NavDisplay(
                            entries = navigationState.toDecoratedEntries(entryProvider),
                            sceneStrategies = listOf(sceneStrategy),
                            onBack = { navigator.goBack() },
                            transitionSpec = { fadeThrough() },
                            popTransitionSpec = { fadeThrough() },
                            predictivePopTransitionSpec = { predictiveBack() },
                        )
                    }
                }
                SnackbarHost(snackbarHost)
                if (showMiniPlayer) {
                    MiniPlayerHost(playerViewModel, playerState, onOpen = { navigator.navigate(NowPlayingRoute) })
                }
            }
        }
    }
}

/** Mini-Player für Musik bzw. Hörbuch. Liest [playerState] erst hier, damit Änderungen nur ihn neu zusammensetzen. */
@Composable
private fun MiniPlayerHost(playerViewModel: PlayerViewModel, playerState: State<PlayerState>, onOpen: () -> Unit) {
    val state by playerState
    if (state.spoken != null) {
        val bookPlayer: BookPlayerViewModel = hiltViewModel()
        val bookSeedColor by bookPlayer.seedColor.collectAsStateWithLifecycle()
        // Ohne Beobachter bliebe die Zeitachse leer und der Anteil am Buch 0.
        val bookTimeline by bookPlayer.timeline.collectAsStateWithLifecycle()
        AlbumColorTheme(bookSeedColor) {
            SpokenMiniPlayer(
                state = state,
                fractionProvider = bookPlayer::bookFraction,
                positionJumps = bookPlayer.positionJumps,
                refreshKey = bookTimeline,
                onClick = onOpen,
                onTogglePlayPause = bookPlayer::togglePlayPause,
                onSkipBack = bookPlayer::skipBack,
                onSkipForward = bookPlayer::skipForward,
            )
        }
    } else if (state.hasMedia) {
        val seedColor by playerViewModel.seedColor.collectAsStateWithLifecycle()
        AlbumColorTheme(seedColor) {
            MiniPlayer(
                state = state,
                positionProvider = { playerViewModel.currentPositionMs },
                positionJumps = playerViewModel.positionJumps,
                onClick = onOpen,
                onTogglePlayPause = playerViewModel::togglePlayPause,
                onNext = playerViewModel::next,
            )
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

/** Die Snackbar-Darstellung eines [SnackbarMessage]; trägt [keep] mit, damit ein neuer Hinweis ihn nicht verdrängt. */
private class AppSnackbarVisuals(message: SnackbarMessage) : SnackbarVisuals {
    override val message: String = message.text
    override val actionLabel: String? = message.actionLabel
    /** Ein Hinweis ohne Ablaufzeit lässt sich immer auch ohne seine Aktion schließen. */
    override val withDismissAction: Boolean = message.duration == SnackbarDuration.Indefinite
    override val duration: SnackbarDuration = message.duration
    val keep: Boolean = message.keep
}
