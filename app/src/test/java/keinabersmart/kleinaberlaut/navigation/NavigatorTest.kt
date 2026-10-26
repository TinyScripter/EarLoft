package keinabersmart.kleinaberlaut.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.assertEquals
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
    fun `tab keeps its back stack when switching away and back`() {
        navigator.navigate(LibraryRoute)
        navigator.navigate(AlbumDetail)
        navigator.navigate(SearchRoute)
        navigator.navigate(LibraryRoute)

        assertEquals(listOf(LibraryRoute, AlbumDetail), state.backStacks.getValue(LibraryRoute).toList())
    }
}
