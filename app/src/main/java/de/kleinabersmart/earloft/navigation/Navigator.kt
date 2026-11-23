package de.kleinabersmart.earloft.navigation

import androidx.navigation3.runtime.NavKey

/** Handles navigation events by adjusting the [NavigationState]. */
class Navigator(private val state: NavigationState) {

    /** The top destination of the current tab. */
    val currentRoute: NavKey get() = currentStack().last()

    /** The setup wizard is in the current tab (also beneath a form opened from it). */
    val inSetup: Boolean get() = currentStack().any { it is SetupRoute }

    /** The wizard's first page is on top; "Back" there ends the wizard instead of going back a page. */
    val onFirstSetupPage: Boolean
        get() {
            val stack = currentStack()
            return stack.last() is SetupRoute && stack.getOrNull(stack.size - 2) !is SetupRoute
        }

    /** Opens the wizard on its first page in the current tab, unless it is already there. */
    fun showSetup() {
        if (!inSetup) currentStack().add(SetupRoute(SetupPage.entries.first()))
    }

    /** Closes the wizard along with everything opened on top of it; the page beneath remains. */
    fun closeSetup() {
        val stack = currentStack()
        val index = stack.indexOfFirst { it is SetupRoute }
        if (index < 0) return
        while (stack.size > index) stack.removeLastOrNull()
    }

    /** Back to the wizard page beneath the forms opened from it (e.g. after subscribing to a podcast there). */
    fun returnToSetup() {
        val stack = currentStack()
        if (!inSetup) return
        while (stack.last() !is SetupRoute) stack.removeLastOrNull()
    }

    /**
     * Programmatic navigation. A tab route shows that tab on its start page (e.g. a notification opening Home with a page on
     * top); every other route goes on top of the current tab, unless that very page is on top already (a quick double tap,
     * "Go to artist" on the artist's own page, review H30). For taps on the navigation bar see [selectTab].
     */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            val stack = state.backStacks.getValue(route)
            while (stack.size > 1) stack.removeLastOrNull()
            state.topLevelRoute = route
        } else {
            val stack = currentStack()
            if (!stack.last().isSamePage(route)) stack.add(route)
        }
    }

    /**
     * Tap on a navigation bar item: another tab comes back with the page the user left there. The tab that is already
     * selected goes back to its start page; if that is already on top, returns `true` so its lists scroll to the top.
     */
    fun selectTab(route: NavKey): Boolean {
        if (route != state.topLevelRoute) {
            state.topLevelRoute = route
            return false
        }
        val stack = currentStack()
        if (stack.size == 1) return true
        while (stack.size > 1) stack.removeLastOrNull()
        return false
    }

    /**
     * The area (music, podcasts, books) was switched: Home and Library, whose content depends on it, go back to their start
     * pages, also the tab not shown, so it doesn't come back with a page of the previous area (review H20). Settings and
     * other tabs keep their page.
     */
    fun closeAreaPages() {
        AREA_TABS.forEach { route ->
            val stack = state.backStacks[route] ?: return@forEach
            while (stack.size > 1) stack.removeLastOrNull()
        }
    }

    /**
     * Shows the playback screen no matter where the app currently is (e.g. after tapping the notification).
     * If it's already in the current tab's stack, it is brought to the front (the queue on top of it is closed)
     * instead of being placed on top a second time.
     */
    fun showNowPlaying() {
        val stack = currentStack()
        val index = stack.lastIndexOf(NowPlayingRoute)
        if (index < 0) {
            stack.add(NowPlayingRoute)
        } else {
            while (stack.size > index + 1) stack.removeLastOrNull()
        }
    }

    /**
     * Closes the full-screen player and the queue over it, if on top; a page opened from there (album, artist) then comes
     * beneath the player instead of over it (review H30), as from the player itself.
     */
    fun closeFullScreenPlayer() {
        val stack = currentStack()
        while (stack.size > 1 && stack.last().isFullScreenPlayer) stack.removeLastOrNull()
    }

    /** Replaces the top page of the current tab (e.g. the preview with the subscription just created). */
    fun replaceTop(route: NavKey) {
        val stack = currentStack()
        if (stack.last() != state.topLevelRoute) stack.removeLastOrNull()
        stack.add(route)
    }

    /** Opens the page of podcast [feedId]; if it is directly beneath the current page, goes back to it. */
    fun showFeed(feedId: String) {
        val stack = currentStack()
        val below = stack.getOrNull(stack.size - 2)
        if (below is PodcastRoute && below.feedId == feedId) stack.removeLastOrNull() else stack.add(PodcastRoute(feedId))
    }

    /**
     * "Back" from page [from] (its back button, "Close", a form that is done): only while it is still on top. A second tap
     * during the exit animation would otherwise close the page beneath as well (review J70, on the watch H6).
     */
    fun goBack(from: NavKey) {
        if (currentRoute == from) goBack()
    }

    /** System back (gesture, key): one page per event, so it isn't tied to a page. */
    fun goBack() {
        val stack = currentStack()
        if (stack.last() == state.topLevelRoute) {
            // Reached the root of a tab → back to the Home tab.
            state.topLevelRoute = state.startRoute
        } else {
            stack.removeLastOrNull()
        }
    }

    /**
     * A subscription was ended: its pages (subscription, info, settings, search) disappear from all stacks, regardless of whether the
     * settings were opened via the subscription page or directly from the long-press menu.
     */
    fun closeFeed(feedId: String) {
        state.backStacks.values.forEach { stack -> stack.removeAll { it.belongsToFeed(feedId) } }
    }

    private fun NavKey.belongsToFeed(feedId: String): Boolean = when (this) {
        is PodcastRoute -> this.feedId == feedId
        is PodcastInfoRoute -> this.feedId == feedId
        is PodcastSettingsRoute -> this.feedId == feedId
        is PodcastSearchRoute -> this.feedId == feedId
        else -> false
    }

    private fun currentStack() =
        state.backStacks[state.topLevelRoute] ?: error("No back stack for ${state.topLevelRoute}")
}

/** The tabs that show the selected area. */
private val AREA_TABS = listOf(HomeRoute, LibraryRoute)
