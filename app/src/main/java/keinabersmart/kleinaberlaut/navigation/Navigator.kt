package keinabersmart.kleinaberlaut.navigation

import androidx.navigation3.runtime.NavKey

/** Verarbeitet Navigationsereignisse, indem es den [NavigationState] anpasst. */
class Navigator(private val state: NavigationState) {

    /** Das oberste Ziel des aktuellen Tabs. */
    val currentRoute: NavKey get() = currentStack().last()

    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            currentStack().add(route)
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
