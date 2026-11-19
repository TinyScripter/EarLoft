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

    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            // Tapping a tab always shows its start page, even if the user navigated deeper there before.
            val stack = state.backStacks.getValue(route)
            while (stack.size > 1) stack.removeLastOrNull()
            state.topLevelRoute = route
        } else {
            currentStack().add(route)
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
        state.backStacks[state.topLevelRoute] ?: error("Kein Back Stack für ${state.topLevelRoute}")
}
