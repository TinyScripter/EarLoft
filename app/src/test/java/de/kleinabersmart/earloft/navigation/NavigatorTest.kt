package de.kleinabersmart.earloft.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NavigatorTest {

    private data object AlbumDetail : NavKey

    private lateinit var state: NavigationState
    private lateinit var navigator: Navigator

    @Before
    fun setUp() {
        state = NavigationState(
            startRoute = HomeRoute,
            topLevelRoute = mutableStateOf(HomeRoute),
            backStacks = TOP_LEVEL_ROUTES.associateWith { NavBackStack(it) },
        )
        navigator = Navigator(state)
    }

    @Test
    fun `selecting a tab switches the top level route`() {
        navigator.navigate(LibraryRoute)

        assertEquals(LibraryRoute, state.topLevelRoute)
    }

    @Test
    fun `sub routes are pushed onto the current tab only`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)

        assertEquals(listOf(LibraryRoute, AlbumDetail), state.backStacks.getValue(LibraryRoute).toList())
        assertEquals(listOf(HomeRoute), state.backStacks.getValue(HomeRoute).toList())
    }

    @Test
    fun `back pops within a tab, then returns to home`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)

        navigator.goBack()
        assertEquals(LibraryRoute, state.topLevelRoute)
        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())

        navigator.goBack()
        assertEquals(HomeRoute, state.topLevelRoute)
    }

    @Test
    fun `programmatic tab navigation shows its root`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)
        navigator.navigate(SettingsRoute)
        navigator.navigate(LibraryRoute)

        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())
    }

    @Test
    fun `a tab keeps its page when switching away and back`() {
        navigator.selectTab(LibraryRoute)
        navigator.navigate(AlbumDetail)
        navigator.selectTab(SettingsRoute)
        val scroll = navigator.selectTab(LibraryRoute)

        assertEquals(listOf(LibraryRoute, AlbumDetail), state.backStacks.getValue(LibraryRoute).toList())
        assertEquals(AlbumDetail, navigator.currentRoute)
        assertFalse(scroll)
    }

    @Test
    fun `tapping the selected tab returns to its root, then asks to scroll up`() {
        navigator.selectTab(LibraryRoute)
        navigator.navigate(AlbumDetail)

        assertFalse(navigator.selectTab(LibraryRoute))
        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())

        assertTrue(navigator.selectTab(LibraryRoute))
        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())
    }

    @Test
    fun `replacing the top swaps the page but never the tab root`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)
        val podcast = PodcastRoute("feed")

        navigator.replaceTop(podcast)
        assertEquals(listOf(LibraryRoute, podcast), state.backStacks.getValue(LibraryRoute).toList())

        navigator.goBack()
        navigator.replaceTop(podcast)
        assertEquals(listOf(LibraryRoute, podcast), state.backStacks.getValue(LibraryRoute).toList())
    }

    @Test
    fun `showing a feed goes back to its page when it lies directly below`() {
        val podcast = PodcastRoute("feed")
        val episode = BookRoute(7)
        navigator.navigate(LibraryRoute)
        navigator.navigate(podcast)
        navigator.navigate(episode)

        navigator.showFeed("feed")
        assertEquals(listOf(LibraryRoute, podcast), state.backStacks.getValue(LibraryRoute).toList())

        navigator.navigate(episode)
        navigator.showFeed("other")
        assertEquals(PodcastRoute("other").feedId, (navigator.currentRoute as PodcastRoute).feedId)
        assertEquals(4, state.backStacks.getValue(LibraryRoute).size)
    }

    @Test
    fun `current route is the top of the selected tab`() {
        assertEquals(HomeRoute, navigator.currentRoute)

        navigator.navigate(LibraryRoute)
        navigator.navigate(NowPlayingRoute)
        assertEquals(NowPlayingRoute, navigator.currentRoute)
        assertTrue(navigator.currentRoute.isFullScreenPlayer)

        navigator.goBack()
        assertEquals(LibraryRoute, navigator.currentRoute)
    }

    @Test
    fun `showNowPlaying opens the player on top of the current tab`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)

        navigator.showNowPlaying()

        assertEquals(listOf(LibraryRoute, AlbumDetail, NowPlayingRoute), state.backStacks.getValue(LibraryRoute).toList())
        assertEquals(NowPlayingRoute, navigator.currentRoute)
    }

    @Test
    fun `showNowPlaying does not stack the player twice`() {
        navigator.showNowPlaying()
        navigator.showNowPlaying()

        assertEquals(listOf(HomeRoute, NowPlayingRoute), state.backStacks.getValue(HomeRoute).toList())
    }

    @Test
    fun `showNowPlaying closes the queue above the player`() {
        navigator.navigate(NowPlayingRoute)
        navigator.navigate(QueueRoute(overPlayer = true))

        navigator.showNowPlaying()

        assertEquals(listOf(HomeRoute, NowPlayingRoute), state.backStacks.getValue(HomeRoute).toList())
        assertEquals(NowPlayingRoute, navigator.currentRoute)
    }

    @Test
    fun `the queue is full screen only above the player`() {
        assertTrue(QueueRoute(overPlayer = true).isFullScreenPlayer)
        assertFalse(QueueRoute(overPlayer = false).isFullScreenPlayer)
    }

    @Test
    fun `back from the player returns to where the user was`() {
        navigator.navigate(LibraryRoute)
        navigator.showNowPlaying()

        navigator.goBack()

        assertEquals(LibraryRoute, navigator.currentRoute)
    }

    @Test
    fun `ending a subscription from the menu closes only its settings`() {
        // Long press on the subscription in the library → settings → unsubscribe.
        navigator.navigate(LibraryRoute)
        navigator.navigate(PodcastSettingsRoute("a"))

        navigator.closeFeed("a")

        assertEquals(listOf<NavKey>(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())
        assertEquals(LibraryRoute, state.topLevelRoute)
    }

    @Test
    fun `ending a subscription closes all its pages in every tab and keeps others`() {
        val other = PodcastRoute("b")
        navigator.navigate(PodcastRoute("a"))
        navigator.navigate(LibraryRoute)
        navigator.navigate(other)
        navigator.navigate(PodcastRoute("a"))
        navigator.navigate(PodcastInfoRoute("a"))
        navigator.navigate(PodcastSettingsRoute("a"))

        navigator.closeFeed("a")

        assertEquals(listOf(LibraryRoute, other), state.backStacks.getValue(LibraryRoute).toList())
        assertEquals(listOf<NavKey>(HomeRoute), state.backStacks.getValue(HomeRoute).toList())
    }

    @Test
    fun `the same album opened twice gets distinct routes`() {
        assertNotEquals(AlbumRoute(albumId = 5), AlbumRoute(albumId = 5))
    }

    @Test
    fun `setup opens once on its first page`() {
        navigator.showSetup()
        navigator.showSetup()

        assertEquals(listOf(HomeRoute, SetupRoute(SetupPage.WELCOME)), state.backStacks.getValue(HomeRoute).toList())
        assertTrue(navigator.inSetup)
        assertTrue(navigator.onFirstSetupPage)
    }

    @Test
    fun `setup pages and forms above them still count as setup`() {
        navigator.showSetup()
        navigator.navigate(SetupRoute(SetupPage.NOTIFICATIONS))
        assertFalse(navigator.onFirstSetupPage)

        navigator.navigate(AddServerRoute)

        assertTrue(navigator.inSetup)
        assertFalse(navigator.onFirstSetupPage)
    }

    @Test
    fun `closing setup removes it with everything above and keeps what was below`() {
        navigator.navigate(SettingsRoute)
        navigator.navigate(SettingsGeneralRoute)
        navigator.showSetup()
        navigator.navigate(SetupRoute(SetupPage.NOTIFICATIONS))
        navigator.navigate(AddServerRoute)

        navigator.closeSetup()

        assertEquals(listOf(SettingsRoute, SettingsGeneralRoute), state.backStacks.getValue(SettingsRoute).toList())
        assertFalse(navigator.inSetup)
    }
}
