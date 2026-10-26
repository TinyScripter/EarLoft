package keinabersmart.kleinaberlaut.navigation

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.navigation3.runtime.NavKey
import keinabersmart.kleinaberlaut.R
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute : NavKey

@Serializable
data object LibraryRoute : NavKey

@Serializable
data object SearchRoute : NavKey

@Serializable
data object DownloadsRoute : NavKey

/** Ziele der Bottom-Bar; jedes hat einen eigenen Back Stack. */
enum class TopLevelDestination(
    val route: NavKey,
    @param:DrawableRes val icon: Int,
    @param:StringRes val label: Int,
) {
    HOME(HomeRoute, R.drawable.ic_nav_home, R.string.nav_home),
    LIBRARY(LibraryRoute, R.drawable.ic_nav_library, R.string.nav_library),
    SEARCH(SearchRoute, R.drawable.ic_nav_search, R.string.nav_search),
    DOWNLOADS(DownloadsRoute, R.drawable.ic_nav_downloads, R.string.nav_downloads),
}

val TOP_LEVEL_ROUTES: Set<NavKey> = TopLevelDestination.entries.map { it.route }.toSet()
