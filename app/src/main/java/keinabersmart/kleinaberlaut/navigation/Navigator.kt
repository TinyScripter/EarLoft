package keinabersmart.kleinaberlaut.navigation

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

    fun goBack() {
        val stack = currentStack()
        if (stack.last() == state.topLevelRoute) {
            // Wurzel eines Tabs erreicht → zurück zum Start-Tab.
            state.topLevelRoute = state.startRoute
        } else {
            stack.removeLastOrNull()
        }
    }

    private fun currentStack() =
        state.backStacks[state.topLevelRoute] ?: error("Kein Back Stack für ${state.topLevelRoute}")
}
