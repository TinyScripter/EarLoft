package de.kleinabersmart.earloft.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.nestedscroll.nestedScroll
import de.kleinabersmart.earloft.ui.player.LocalMiniPlayerScrollHide
import de.kleinabersmart.earloft.ui.player.MiniPlayerScrollHide
import de.kleinabersmart.earloft.ui.player.itemKey
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
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.window.core.layout.WindowSizeClass
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.ui.theme.PlayerColorTheme
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.data.backup.MissingCredentials
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import de.kleinabersmart.earloft.notification.NewContentTarget
import de.kleinabersmart.earloft.navigation.AboutRoute
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.navigation.AddAudiobookshelfRoute
import de.kleinabersmart.earloft.navigation.BookRoute
import de.kleinabersmart.earloft.navigation.PodcastInfoRoute
import de.kleinabersmart.earloft.navigation.BookQueueRoute
import de.kleinabersmart.earloft.navigation.PodcastQueueRoute
import de.kleinabersmart.earloft.navigation.PodcastQueueSearchRoute
import de.kleinabersmart.earloft.navigation.PodcastHistoryRoute
import de.kleinabersmart.earloft.navigation.BookmarksRoute
import de.kleinabersmart.earloft.ui.spoken.BookmarksScreen
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.ui.spoken.PodcastHistoryScreen
import de.kleinabersmart.earloft.navigation.PodcastRoute
import de.kleinabersmart.earloft.navigation.PodcastSearchRoute
import de.kleinabersmart.earloft.navigation.PodcastSettingsRoute
import de.kleinabersmart.earloft.navigation.AddPodcastRoute
import de.kleinabersmart.earloft.navigation.ArdBookRoute
import de.kleinabersmart.earloft.navigation.ArdShowRoute
import de.kleinabersmart.earloft.navigation.ArdSoundsRoute
import de.kleinabersmart.earloft.navigation.PodcastPreviewRoute
import de.kleinabersmart.earloft.ui.spoken.AddPodcastScreen
import de.kleinabersmart.earloft.ui.spoken.ArdBookPreviewScreen
import de.kleinabersmart.earloft.ui.spoken.ArdShowScreen
import de.kleinabersmart.earloft.ui.spoken.ArdSoundsScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastPreviewScreen
import de.kleinabersmart.earloft.navigation.AddServerRoute
import de.kleinabersmart.earloft.navigation.AlbumRoute
import de.kleinabersmart.earloft.navigation.ArtistRoute
import de.kleinabersmart.earloft.navigation.GenreRoute
import de.kleinabersmart.earloft.navigation.DownloadsRoute
import de.kleinabersmart.earloft.navigation.EditAudiobookshelfRoute
import de.kleinabersmart.earloft.navigation.EditServerRoute
import de.kleinabersmart.earloft.navigation.FavoritesRoute
import de.kleinabersmart.earloft.navigation.HomeRoute
import de.kleinabersmart.earloft.navigation.LibraryRoute
import de.kleinabersmart.earloft.navigation.LicensesRoute
import de.kleinabersmart.earloft.navigation.PrivacyRoute
import de.kleinabersmart.earloft.navigation.LocalSharedTransitionScope
import de.kleinabersmart.earloft.navigation.ListDetailSceneStrategy
import de.kleinabersmart.earloft.navigation.ListDetailSceneStrategy.Companion.detailPane
import de.kleinabersmart.earloft.navigation.ListDetailSceneStrategy.Companion.listPane
import de.kleinabersmart.earloft.navigation.ListDetailSceneStrategy.Companion.nestedDetailPane
import de.kleinabersmart.earloft.navigation.Navigator
import de.kleinabersmart.earloft.navigation.BookGroupKind
import de.kleinabersmart.earloft.navigation.BookGroupRoute
import de.kleinabersmart.earloft.navigation.NowPlayingRoute
import de.kleinabersmart.earloft.navigation.PlaylistRoute
import de.kleinabersmart.earloft.navigation.QueueRoute
import de.kleinabersmart.earloft.navigation.SearchRoute
import de.kleinabersmart.earloft.navigation.SettingsAudiobooksRoute
import de.kleinabersmart.earloft.navigation.SettingsGeneralRoute
import de.kleinabersmart.earloft.navigation.SettingsMusicRoute
import de.kleinabersmart.earloft.navigation.SettingsPodcastsRoute
import de.kleinabersmart.earloft.navigation.SettingsRoute
import de.kleinabersmart.earloft.navigation.SetupRoute
import de.kleinabersmart.earloft.navigation.WatchSettingsRoute
import de.kleinabersmart.earloft.navigation.TOP_LEVEL_ROUTES
import de.kleinabersmart.earloft.navigation.TopLevelDestination
import de.kleinabersmart.earloft.navigation.isFullScreenPlayer
import de.kleinabersmart.earloft.navigation.rememberNavigationState
import de.kleinabersmart.earloft.ui.components.LocalOfflineController
import de.kleinabersmart.earloft.ui.components.LocalMixController
import de.kleinabersmart.earloft.ui.components.LocalShareController
import de.kleinabersmart.earloft.ui.components.LocalWatchController
import de.kleinabersmart.earloft.ui.components.rememberOfflineController
import de.kleinabersmart.earloft.ui.components.rememberMixController
import de.kleinabersmart.earloft.ui.components.rememberShareController
import de.kleinabersmart.earloft.ui.components.rememberWatchController
import de.kleinabersmart.earloft.ui.downloads.DownloadsScreen
import de.kleinabersmart.earloft.ui.home.HomeScreen
import de.kleinabersmart.earloft.ui.library.AlbumScreen
import de.kleinabersmart.earloft.ui.library.ArtistScreen
import de.kleinabersmart.earloft.ui.library.GenreScreen
import de.kleinabersmart.earloft.ui.library.FavoritesScreen
import de.kleinabersmart.earloft.ui.library.LibraryScreen
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.player.MiniPlayer
import de.kleinabersmart.earloft.ui.player.NowPlayingScreen
import de.kleinabersmart.earloft.ui.player.PlayerViewModel
import de.kleinabersmart.earloft.ui.player.QueueScreen
import de.kleinabersmart.earloft.ui.player.swipeToDismiss
import de.kleinabersmart.earloft.ui.playlists.PlaylistScreen
import de.kleinabersmart.earloft.ui.search.SearchScreen
import de.kleinabersmart.earloft.ui.settings.AboutScreen
import de.kleinabersmart.earloft.ui.settings.LicensesScreen
import de.kleinabersmart.earloft.ui.settings.PrivacyScreen
import de.kleinabersmart.earloft.ui.settings.AudiobookSettingsScreen
import de.kleinabersmart.earloft.ui.settings.GeneralSettingsScreen
import de.kleinabersmart.earloft.ui.settings.MusicSettingsScreen
import de.kleinabersmart.earloft.ui.settings.PodcastsSettingsScreen
import de.kleinabersmart.earloft.ui.settings.ServerFormScreen
import de.kleinabersmart.earloft.ui.settings.SettingsScreen
import de.kleinabersmart.earloft.ui.settings.WatchSettingsScreen
import de.kleinabersmart.earloft.ui.setup.SetupExitDialog
import de.kleinabersmart.earloft.ui.setup.SetupScreen
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.ui.snackbar.SnackbarViewModel
import de.kleinabersmart.earloft.ui.spoken.AudiobookshelfFormScreen
import de.kleinabersmart.earloft.ui.spoken.BookLaunchEffect
import de.kleinabersmart.earloft.ui.spoken.BookPlayerScreen
import de.kleinabersmart.earloft.ui.spoken.BookPlayerViewModel
import de.kleinabersmart.earloft.ui.spoken.BookScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastHomeScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastInfoScreen
import de.kleinabersmart.earloft.ui.spoken.BookQueueScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastQueueScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastQueueSearchScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastSearchScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastSettingsScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastLibraryScreen
import de.kleinabersmart.earloft.ui.spoken.PodcastScreen
import de.kleinabersmart.earloft.ui.spoken.SpokenHomeScreen
import de.kleinabersmart.earloft.ui.spoken.SpokenLibraryScreen
import de.kleinabersmart.earloft.ui.spoken.SpokenMiniPlayer
import de.kleinabersmart.earloft.ui.spoken.BookGroupScreen
import de.kleinabersmart.earloft.ui.spoken.SpokenModeHost
import de.kleinabersmart.earloft.ui.spoken.LocalBookDownloads
import de.kleinabersmart.earloft.ui.spoken.LocalFeedDownloads
import de.kleinabersmart.earloft.ui.spoken.bookFraction
import de.kleinabersmart.earloft.ui.spoken.rememberBookDownloads
import de.kleinabersmart.earloft.ui.spoken.rememberFeedDownloads
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.ui.components.LocalScrollToTop
import de.kleinabersmart.earloft.ui.components.ScrollToTopRequests
import de.kleinabersmart.earloft.ui.components.LocalCollectionFavorites
import de.kleinabersmart.earloft.ui.components.rememberCollectionFavorites

@Composable
fun EarLoftApp(
    openNowPlaying: Boolean = false,
    onNowPlayingOpened: () -> Unit = {},
    sharedFeedUrl: String? = null,
    onSharedFeedOpened: () -> Unit = {},
    newContentTarget: NewContentTarget? = null,
    onNewContentOpened: () -> Unit = {},
    alwaysShowNavigationBar: Boolean = true,
    hideMiniPlayerOnScroll: Boolean = true,
    showSetup: Boolean = false,
    onSetupFinished: () -> Unit = {},
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val navigationState = rememberNavigationState(
        startRoute = HomeRoute,
        topLevelRoutes = TOP_LEVEL_ROUTES,
    )
    // The wizard is already on top in the first frame; from an effect it would only come afterwards and the home page would flash briefly.
    val navigator = remember(navigationState) { Navigator(navigationState).apply { if (showSetup) showSetup() } }
    LaunchedEffect(openNowPlaying) {
        if (openNowPlaying) {
            navigator.showNowPlaying()
            onNowPlayingOpened()
        }
    }
    // A shared podcast address opens the preview like a directory hit; until the feed is loaded, the
    // address serves as the title.
    LaunchedEffect(sharedFeedUrl) {
        if (sharedFeedUrl != null) {
            navigator.navigate(PodcastPreviewRoute(sharedFeedUrl, title = sharedFeedUrl, author = "", imageUrl = null))
            onSharedFeedOpened()
        }
    }
    // Tap on a new-content notification (the area is already selected): Home, for exactly one album, podcast or
    // book its page on top.
    LaunchedEffect(newContentTarget) {
        val target = newContentTarget ?: return@LaunchedEffect
        navigator.navigate(HomeRoute)
        when {
            target.albumId != null -> navigator.navigate(AlbumRoute(target.albumId))
            target.feedId != null -> navigator.navigate(PodcastRoute(target.feedId))
            target.bookId != null -> navigator.navigate(BookRoute(target.bookId))
            target.ardShowId != null -> {
                navigator.navigate(ArdSoundsRoute())
                navigator.navigate(ArdShowRoute(target.ardShowId, target.ardShowTitle.orEmpty()))
            }
        }
        onNewContentOpened()
    }
    // Setup wizard (concept 15): on first start on top of Home. "Back" on its first page asks whether to end it.
    var confirmSetupExit by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(showSetup) {
        if (showSetup) navigator.showSetup()
    }
    val finishSetup: () -> Unit = {
        confirmSetupExit = false
        navigator.closeSetup()
        onSetupFinished()
    }

    // Only derive here: otherwise every player change (play/pause, title, queue, speed) would recompose navigation and
    // scaffold. Only the mini player reads the full state.
    val playerState = playerViewModel.state.collectAsStateWithLifecycle()
    val playsSpoken by remember { derivedStateOf { playerState.value.spoken != null } }

    // From medium width on (tablet, foldable, phone in landscape) navigation rail instead of bottom bar,
    // from expanded width on additionally list and detail side by side.
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
    val openGenre: (String) -> Unit = { navigator.navigate(GenreRoute(it)) }
    val openBook: (Long) -> Unit = { navigator.navigate(BookRoute(it)) }
    val openPodcast: (String) -> Unit = { navigator.navigate(PodcastRoute(it)) }
    val openPodcastSettings: (String) -> Unit = { navigator.navigate(PodcastSettingsRoute(it)) }
    val openAddPodcast: () -> Unit = { navigator.navigate(AddPodcastRoute()) }
    val setupAudiobookshelf: () -> Unit = { navigator.navigate(AddAudiobookshelfRoute) }
    val openArdSounds: () -> Unit = { navigator.navigate(ArdSoundsRoute()) }
    val editAudiobookshelf: (String) -> Unit = { navigator.navigate(EditAudiobookshelfRoute(it)) }
    // Mode changed: close the detail pane of the previous mode (tab back to its start page).
    val closeDetail: () -> Unit = { navigator.navigate(navigationState.topLevelRoute) }
    val openSearch: () -> Unit = { navigator.navigate(SearchRoute()) }
    val addMusicServer: () -> Unit = { navigator.navigate(AddServerRoute) }
    // From an empty area directly to the area switches (Settings → General).
    val openAreaSettings: () -> Unit = {
        navigator.navigate(SettingsRoute)
        navigator.navigate(SettingsGeneralRoute)
    }

    // Reselecting a tab on its start page scrolls the lists there back to the top.
    val scrollToTop = remember { TOP_LEVEL_ROUTES.associateWith { ScrollToTopRequests() } }

    val entryProvider = entryProvider {
        entry<HomeRoute> {
            CompositionLocalProvider(LocalScrollToTop provides scrollToTop.getValue(HomeRoute).events) {
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
                    books = {
                        SpokenHomeScreen(
                            onBookClick = openBook,
                            onOpenQueue = { navigator.navigate(BookQueueRoute) },
                            onSetup = setupAudiobookshelf,
                            onEditServer = editAudiobookshelf,
                            onOpenArdSounds = openArdSounds,
                        )
                    },
                    onModeChange = closeDetail,
                    onSearch = openSearch,
                    onOpenAreaSettings = openAreaSettings,
                )
            }
        }
        entry<LibraryRoute>(metadata = listPane()) {
            CompositionLocalProvider(LocalScrollToTop provides scrollToTop.getValue(LibraryRoute).events) {
                SpokenModeHost(
                    music = {
                        LibraryScreen(
                            onAlbumClick = openAlbum,
                            onArtistClick = openArtist,
                            onGenreClick = openGenre,
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
                            onOpenHistory = { navigator.navigate(PodcastHistoryRoute) },
                            onOpenBookmarks = { navigator.navigate(BookmarksRoute(BookKind.EPISODE)) },
                        )
                    },
                    books = {
                        SpokenLibraryScreen(
                            onBookClick = openBook,
                            onGroupClick = { kind, name -> navigator.navigate(BookGroupRoute(kind, name)) },
                            onOpenBookmarks = { navigator.navigate(BookmarksRoute(BookKind.BOOK)) },
                            onSetup = setupAudiobookshelf,
                            onEditServer = editAudiobookshelf,
                        )
                    },
                    onModeChange = closeDetail,
                    onSearch = openSearch,
                    onOpenAreaSettings = openAreaSettings,
                )
            }
        }
        entry<BookRoute> { route ->
            // "Play" starts or resumes the book and then opens the playback screen.
            BookScreen(
                bookId = route.bookId,
                onBack = navigator::goBack,
                onOpenPlayer = navigator::showNowPlaying,
                onOpenFeed = navigator::showFeed,
                onOpenGroup = { kind, name -> navigator.navigate(BookGroupRoute(kind, name)) },
                // After uploading, the book on the server takes the place of this page.
                onReplaceBook = { navigator.replaceTop(BookRoute(it)) },
            )
        }
        entry<ArdSoundsRoute>(metadata = detailPane()) {
            ArdSoundsScreen(
                onBack = navigator::goBack,
                onShow = { navigator.navigate(ArdShowRoute(it.id, it.title)) },
                onBook = { navigator.navigate(ArdBookRoute(it.key, it.title)) },
                onOpenBook = openBook,
            )
        }
        entry<ArdShowRoute>(metadata = nestedDetailPane()) { route ->
            ArdShowScreen(
                showId = route.showId,
                title = route.title,
                onBack = navigator::goBack,
                onBook = { navigator.navigate(ArdBookRoute(it.key, it.title)) },
                onOpenBook = openBook,
            )
        }
        entry<ArdBookRoute>(metadata = nestedDetailPane()) { route ->
            // In the library the book page takes the place of the preview; after "Play" the playback screen opens.
            ArdBookPreviewScreen(
                key = route.key,
                title = route.title,
                onBack = navigator::goBack,
                onAdded = { bookId, openPlayer ->
                    navigator.replaceTop(BookRoute(bookId))
                    if (openPlayer) navigator.showNowPlaying()
                },
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
                // The new subscription takes the place of the page, as after the dialog previously.
                onFeedAdded = { navigator.replaceTop(PodcastRoute(it)) },
                onOpenFeed = openPodcast,
                onPreview = { navigator.navigate(PodcastPreviewRoute(it.feedUrl, it.title, it.author, it.imageUrl)) },
                onOpenSettings = { navigator.navigate(SettingsPodcastsRoute) },
            )
        }
        entry<PodcastPreviewRoute>(metadata = nestedDetailPane()) { route ->
            // The preview only loads and then makes room for the page of the (trial) subscription; Back leads to the hits.
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
                // The subscription is gone: close all its pages (the settings can also be opened directly from the long-press menu).
                onUnsubscribed = { navigator.closeFeed(route.feedId) },
            )
        }
        entry<BookGroupRoute>(metadata = detailPane()) { route ->
            BookGroupScreen(
                kind = route.kind,
                name = route.name,
                onBack = navigator::goBack,
                onBookClick = openBook,
                onGroupClick = { kind, name -> navigator.navigate(BookGroupRoute(kind, name)) },
            )
        }
        entry<AddAudiobookshelfRoute>(metadata = detailPane()) { AudiobookshelfFormScreen(serverId = null, onDone = navigator::goBack) }
        entry<EditAudiobookshelfRoute>(metadata = detailPane()) { route ->
            AudiobookshelfFormScreen(serverId = route.serverId, onDone = navigator::goBack)
        }
        entry<FavoritesRoute>(metadata = detailPane()) {
            FavoritesScreen(onBack = navigator::goBack, onAlbumClick = openAlbum, onArtistClick = openArtist)
        }
        entry<BookmarksRoute>(metadata = detailPane()) { route ->
            BookmarksScreen(kind = route.kind, onBack = navigator::goBack, onBookClick = openBook)
        }
        entry<PodcastHistoryRoute>(metadata = detailPane()) {
            PodcastHistoryScreen(onBack = navigator::goBack, onEpisodeClick = openBook, onFeedClick = openPodcast)
        }
        entry<PlaylistRoute>(metadata = detailPane()) { route ->
            PlaylistScreen(playlistId = route.playlistId, onBack = navigator::goBack)
        }
        entry<SearchRoute> {
            // The area of Home or Library determines where to search.
            SearchScreen(
                onBack = navigator::goBack,
                onAlbumClick = openAlbum,
                onArtistClick = openArtist,
                onGenreClick = openGenre,
                onBookClick = openBook,
                onFeedClick = openPodcast,
                onFeedSettings = openPodcastSettings,
                onAuthorClick = { navigator.navigate(BookGroupRoute(BookGroupKind.AUTHOR, it)) },
                onNarratorClick = { navigator.navigate(BookGroupRoute(BookGroupKind.NARRATOR, it)) },
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
            AlbumScreen(albumId = route.albumId, onBack = navigator::goBack, onArtistClick = openArtist)
        }
        entry<GenreRoute>(metadata = detailPane()) { route ->
            GenreScreen(genre = route.name, onBack = navigator::goBack, onAlbumClick = openAlbum)
        }
        entry<ArtistRoute>(metadata = detailPane()) { route ->
            ArtistScreen(artistId = route.artistId, onBack = navigator::goBack, onAlbumClick = openAlbum)
        }
        entry<NowPlayingRoute> {
            // The playing item decides, not the mode: an audiobook has its own screen.
            if (playsSpoken) {
                BookPlayerScreen(onClose = navigator::goBack, onEpisodeClick = openBook)
            } else {
                // Album and artist close the player and open on the page beneath it.
                NowPlayingScreen(
                    onClose = navigator::goBack,
                    onOpenQueue = { navigator.navigate(QueueRoute(overPlayer = true)) },
                    onOpenAlbum = {
                        navigator.goBack()
                        openAlbum(it)
                    },
                    onOpenArtist = {
                        navigator.goBack()
                        openArtist(it)
                    },
                )
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
        entry<BookQueueRoute> { BookQueueScreen(onBack = navigator::goBack, onBookClick = openBook) }
        entry<SettingsRoute>(metadata = listPane()) {
            CompositionLocalProvider(LocalScrollToTop provides scrollToTop.getValue(SettingsRoute).events) {
                SettingsScreen(
                    onOpenGeneral = { navigator.navigate(SettingsGeneralRoute) },
                    onOpenMusic = { navigator.navigate(SettingsMusicRoute) },
                    onOpenPodcasts = { navigator.navigate(SettingsPodcastsRoute) },
                    onOpenAudiobooks = { navigator.navigate(SettingsAudiobooksRoute) },
                    onOpenWatch = { navigator.navigate(WatchSettingsRoute) },
                    onOpenAbout = { navigator.navigate(AboutRoute) },
                )
            }
        }
        entry<SettingsGeneralRoute>(metadata = detailPane()) {
            GeneralSettingsScreen(
                onBack = navigator::goBack,
                onOpenDownloads = { navigator.navigate(DownloadsRoute) },
                onOpenSetup = navigator::showSetup,
            )
        }
        entry<SettingsMusicRoute>(metadata = detailPane()) {
            MusicSettingsScreen(
                onBack = navigator::goBack,
                onAddServer = { navigator.navigate(AddServerRoute) },
                onEditServer = { navigator.navigate(EditServerRoute(it)) },
            )
        }
        entry<SettingsPodcastsRoute>(metadata = detailPane()) { PodcastsSettingsScreen(onBack = navigator::goBack, onOpenFeed = openPodcast) }
        entry<SettingsAudiobooksRoute>(metadata = detailPane()) {
            AudiobookSettingsScreen(
                onBack = navigator::goBack,
                onAddServer = setupAudiobookshelf,
                onEditServer = { navigator.navigate(EditAudiobookshelfRoute(it)) },
            )
        }
        entry<WatchSettingsRoute>(metadata = detailPane()) { WatchSettingsScreen(onBack = navigator::goBack) }
        entry<AboutRoute>(metadata = detailPane()) {
            AboutScreen(
                onBack = navigator::goBack,
                onOpenLicenses = { navigator.navigate(LicensesRoute) },
                onOpenPrivacy = { navigator.navigate(PrivacyRoute) },
            )
        }
        entry<LicensesRoute>(metadata = detailPane()) { LicensesScreen(onBack = navigator::goBack) }
        entry<PrivacyRoute>(metadata = detailPane()) { PrivacyScreen(onBack = navigator::goBack) }
        entry<AddServerRoute>(metadata = detailPane()) { ServerFormScreen(sourceId = null, onDone = navigator::goBack) }
        entry<EditServerRoute>(metadata = detailPane()) { route ->
            ServerFormScreen(sourceId = route.sourceId, onDone = navigator::goBack)
        }
        entry<SetupRoute> { route ->
            SetupScreen(
                page = route.page,
                onNext = { navigator.navigate(SetupRoute(it)) },
                onBack = navigator::goBack,
                onSkip = { confirmSetupExit = true },
                onFinish = finishSetup,
                onAddMusicServer = addMusicServer,
                onEditMusicServer = { navigator.navigate(EditServerRoute(it)) },
                onAddPodcast = openAddPodcast,
                onAddAudiobookshelf = setupAudiobookshelf,
                onEditAudiobookshelf = { navigator.navigate(EditAudiobookshelfRoute(it)) },
            )
        }
    }

    // Navigation bar (bottom or as a rail on the left) optionally stays visible in the player too; the mini player is always omitted there.
    // Neither of them in the wizard, not even in the forms opened from it; it only ends via its buttons.
    val showMiniPlayer = !navigator.currentRoute.isFullScreenPlayer && !navigator.inSetup
    val showNavigation = !navigator.inSetup && (alwaysShowNavigationBar || showMiniPlayer)
    val navigationSuiteState = rememberNavigationSuiteScaffoldState()
    LaunchedEffect(showNavigation) {
        if (showNavigation) navigationSuiteState.show() else navigationSuiteState.hide()
    }
    // Scrolled out of the way only for the current page: another page, area or tab brings it back.
    val miniPlayerScrollHide = remember { MiniPlayerScrollHide() }
    LaunchedEffect(navigator.currentRoute, navigationState.topLevelRoute) { miniPlayerScrollHide.show() }
    // Something starts playing (also another title): the mini player shows what it is. Read here via snapshotFlow, so the app
    // doesn't recompose on every player state change.
    LaunchedEffect(playerState) {
        snapshotFlow { playerState.value.let { it.itemKey to it.isPlaying } }.collect { (_, playing) ->
            if (playing) miniPlayerScrollHide.show()
        }
    }

    val snackbars = hiltViewModel<SnackbarViewModel>().snackbars
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(snackbars) {
        snackbars.messages.collect { message ->
            // A new hint replaces the visible one (e.g. "Refreshing" with the result), unless that one should stay;
            // then the new one waits behind it.
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
        LocalShareController provides rememberShareController(),
        LocalMixController provides rememberMixController(),
        LocalCollectionFavorites provides rememberCollectionFavorites(),
        LocalBookDownloads provides rememberBookDownloads(),
        LocalFeedDownloads provides rememberFeedDownloads(),
        LocalMiniPlayerScrollHide provides miniPlayerScrollHide,
    ) {
        SyncNoticeEffect()
        QueuedForMusicEffect()
        RefreshEventEffect()
        // In the wizard without "Enter": it lists the servers itself, and switching to the settings would close it.
        RestoreNoticeEffect(
            onEnter = { missing: MissingCredentials ->
                navigator.navigate(SettingsRoute)
                when {
                    missing.audiobookServers == 0 -> navigator.navigate(SettingsMusicRoute)
                    missing.musicServers == 0 -> navigator.navigate(SettingsAudiobooksRoute)
                }
            }.takeUnless { navigator.inSetup },
        )
        BookLaunchEffect()
        // The screens take care of the status bar themselves and – in the full-screen player – of the navigation bar.
        NavigationSuiteScaffold(
            navigationItems = {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationSuiteItem(
                        selected = destination.route == navigationState.topLevelRoute,
                        onClick = {
                            if (navigator.selectTab(destination.route)) {
                                scrollToTop.getValue(destination.route).request()
                                miniPlayerScrollHide.show()
                            }
                        },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                        navigationSuiteType = navigationSuiteType,
                    )
                }
            },
            navigationSuiteType = navigationSuiteType,
            state = navigationSuiteState,
        ) {
            // With a bottom bar the bottom inset is already consumed; with a rail this keeps lists and mini player
            // away from the gesture bar.
            val bottomInsets = if (showNavigation) WindowInsets.navigationBars.only(WindowInsetsSides.Bottom) else WindowInsets(0)
            // Below the pages sit snackbar, mini player and bar: their height counts as already consumed for the keyboard. Otherwise
            // `imePadding()` in forms pushes up by the full keyboard height, and buttons slide out of view above empty space.
            var belowPages by remember { mutableStateOf(0.dp) }
            val density = LocalDensity.current
            Column(Modifier.windowInsetsPadding(bottomInsets)) {
                // Shared elements (the header of Home and Library) stay in place during page transitions.
                SharedTransitionLayout(
                    Modifier
                        .weight(1f)
                        // Sees the scrolling of every page; lists below pull-to-refresh report to it themselves.
                        .nestedScroll(miniPlayerScrollHide)
                        .onGloballyPositioned { coordinates ->
                            val bottom = coordinates.positionInRoot().y + coordinates.size.height
                            val gap = coordinates.findRootCoordinates().size.height - bottom
                            belowPages = with(density) { gap.coerceAtLeast(0f).toDp() }
                        }
                        // As `WindowInsets`: those are united with what's already consumed. `PaddingValues` would count the
                        // navigation bar a second time (it's in `belowPages` and already consumed above, review V12).
                        .consumeWindowInsets(WindowInsets(bottom = belowPages)),
                ) {
                    CompositionLocalProvider(LocalSharedTransitionScope provides this) {
                        NavDisplay(
                            entries = navigationState.toDecoratedEntries(entryProvider),
                            sceneStrategies = listOf(sceneStrategy),
                            onBack = { if (navigator.onFirstSetupPage) confirmSetupExit = true else navigator.goBack() },
                            transitionSpec = { fadeThrough() },
                            popTransitionSpec = { fadeThrough() },
                            predictivePopTransitionSpec = { predictiveBack() },
                        )
                    }
                }
                SnackbarHost(snackbarHost)
                // Slides down out of the way and back up; the pages above grow and shrink with it.
                AnimatedVisibility(
                    visible = showMiniPlayer && !(hideMiniPlayerOnScroll && miniPlayerScrollHide.hidden),
                    enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically { it },
                    exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically { it },
                ) {
                    MiniPlayerHost(playerViewModel, playerState, onOpen = { navigator.navigate(NowPlayingRoute) })
                }
            }
        }
        if (confirmSetupExit) SetupExitDialog(onConfirm = finishSetup, onDismiss = { confirmSetupExit = false })
    }
}

/**
 * Mini player for music or audiobook. Reads [playerState] only here so changes recompose only it.
 * When paused (and not casting) it can be swiped away downwards or sideways, with "Undo"; it stays gone until the next
 * playback, even across a restart.
 */
@Composable
private fun MiniPlayerHost(playerViewModel: PlayerViewModel, playerState: State<PlayerState>, onOpen: () -> Unit) {
    val state by playerState
    val hidden by playerViewModel.miniPlayerHidden.collectAsStateWithLifecycle()
    val dismissible = !state.isPlaying && !state.isRemote
    if (hidden && dismissible) return
    val snackbars = LocalSnackbars.current
    val hiddenText = stringResource(R.string.mini_player_hidden)
    val undoText = stringResource(R.string.action_undo)
    val dismiss = Modifier.swipeToDismiss(dismissible, stringResource(R.string.mini_player_hide)) {
        playerViewModel.hideMiniPlayer()
        snackbars.show(hiddenText, actionLabel = undoText, onAction = playerViewModel::showMiniPlayer)
    }
    if (state.spoken != null) {
        val bookPlayer: BookPlayerViewModel = hiltViewModel()
        val bookSeedColor by bookPlayer.seedColor.collectAsStateWithLifecycle()
        // Without an observer the timeline would stay empty and the book progress 0.
        val bookTimeline by bookPlayer.timeline.collectAsStateWithLifecycle()
        PlayerColorTheme(bookSeedColor) {
            SpokenMiniPlayer(
                state = state,
                fractionProvider = bookPlayer::bookFraction,
                positionJumps = bookPlayer.positionJumps,
                refreshKey = bookTimeline,
                onClick = onOpen,
                onTogglePlayPause = bookPlayer::togglePlayPause,
                onSkipBack = bookPlayer::skipBack,
                onSkipForward = bookPlayer::skipForward,
                modifier = dismiss,
            )
        }
    } else if (state.hasMedia) {
        val seedColor by playerViewModel.seedColor.collectAsStateWithLifecycle()
        PlayerColorTheme(seedColor) {
            MiniPlayer(
                state = state,
                positionProvider = { playerViewModel.currentPositionMs },
                positionJumps = playerViewModel.positionJumps,
                onClick = onOpen,
                onTogglePlayPause = playerViewModel::togglePlayPause,
                onNext = playerViewModel::next,
                modifier = dismiss,
            )
        }
    }
}

/**
 * Back gesture: the current screen follows the finger and shrinks down to 70 %, the previous one lies behind it.
 * At the end of the animation (after release) the old screen fades out and the new one is in the foreground.
 */
private fun predictiveBack() = ContentTransform(
    targetContentEnter = fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.95f),
    initialContentExit = scaleOut(tween(300), targetScale = 0.7f) +
        slideOutHorizontally(tween(300)) { it / 4 } +
        fadeOut(tween(100, delayMillis = 200)),
    targetContentZIndex = -1f,
)

/** Fade through (Material): the old screen briefly fades out, the new one fades in with a delay and scales slightly from 0.92 to 1. */
private fun fadeThrough() = ContentTransform(
    targetContentEnter = fadeIn(tween(210, delayMillis = 90)) + scaleIn(tween(210, delayMillis = 90), initialScale = 0.92f),
    initialContentExit = fadeOut(tween(90)),
)

/** The snackbar visuals of a [SnackbarMessage]; carries [keep] along so a new hint doesn't displace it. */
private class AppSnackbarVisuals(message: SnackbarMessage) : SnackbarVisuals {
    override val message: String = message.text
    override val actionLabel: String? = message.actionLabel
    /** A hint without a timeout can always be dismissed even without its action. */
    override val withDismissAction: Boolean = message.duration == SnackbarDuration.Indefinite
    override val duration: SnackbarDuration = message.duration
    val keep: Boolean = message.keep
}
