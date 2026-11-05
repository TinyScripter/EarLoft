package de.kleinabersmart.kleinaberlaut.navigation

import androidx.navigation3.runtime.NavKey

/** Verarbeitet Navigationsereignisse, indem es den [NavigationState] anpasst. */
class Navigator(private val state: NavigationState) {

    /** Das oberste Ziel des aktuellen Tabs. */
    val currentRoute: NavKey get() = currentStack().last()

    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            // Ein Tipp auf einen Tab zeigt immer dessen Startseite, auch wenn dort zuvor tiefer navigiert wurde.
            val stack = state.backStacks.getValue(route)
            while (stack.size > 1) stack.removeLastOrNull()
            state.topLevelRoute = route
        } else {
            currentStack().add(route)
        }
    }

    /**
     * Zeigt die Wiedergabe-Ansicht, egal wo die App gerade steht (z. B. nach dem Tipp auf die Benachrichtigung).
     * Liegt sie schon im Stack des aktuellen Tabs, kommt sie nach vorn (die Warteschlange darüber wird geschlossen),
     * statt ein zweites Mal daraufzulegen.
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

    /** Ersetzt die oberste Seite des aktuellen Tabs (z. B. die Vorschau durch das gerade abonnierte Abo). */
    fun replaceTop(route: NavKey) {
        val stack = currentStack()
        if (stack.last() != state.topLevelRoute) stack.removeLastOrNull()
        stack.add(route)
    }

    /** Öffnet die Seite des Podcasts [feedId]; liegt sie direkt unter der aktuellen Seite, geht es dorthin zurück. */
    fun showFeed(feedId: String) {
        val stack = currentStack()
        val below = stack.getOrNull(stack.size - 2)
        if (below is PodcastRoute && below.feedId == feedId) stack.removeLastOrNull() else stack.add(PodcastRoute(feedId))
    }

    fun goBack() {
        val stack = currentStack()
        if (stack.last() == state.topLevelRoute) {
            // Wurzel eines Tabs erreicht → zurück zum Start-Tab.
            state.topLevelRoute = state.startRoute
        } else {
            stack.removeLastOrNull()
        }
    }

    /**
     * Ein Abo wurde beendet: Seine Seiten (Abo, Info, Einstellungen, Suche) verschwinden aus allen Stacks, egal ob die
     * Einstellungen über die Abo-Seite oder direkt aus dem Langklick-Menü geöffnet wurden.
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
