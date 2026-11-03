package keinabersmart.kleinaberlaut.navigation

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
    fun `tab shows its root again when switching away and back`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)
        navigator.navigate(SettingsRoute)
        navigator.navigate(LibraryRoute)

        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())
    }

    @Test
    fun `tapping the selected tab returns to its root`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)
        navigator.navigate(LibraryRoute)

        assertEquals(listOf(LibraryRoute), state.backStacks.getValue(LibraryRoute).toList())
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
    fun `the same album opened twice gets distinct routes`() {
        assertNotEquals(AlbumRoute(albumId = 5), AlbumRoute(albumId = 5))
    }
}
