package keinabersmart.kleinaberlaut.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.navigation.DownloadsRoute
import keinabersmart.kleinaberlaut.navigation.HomeRoute
import keinabersmart.kleinaberlaut.navigation.LibraryRoute
import keinabersmart.kleinaberlaut.navigation.Navigator
import keinabersmart.kleinaberlaut.navigation.SearchRoute
import keinabersmart.kleinaberlaut.navigation.TOP_LEVEL_ROUTES
import keinabersmart.kleinaberlaut.navigation.TopLevelDestination
import keinabersmart.kleinaberlaut.navigation.rememberNavigationState

@Composable
fun KleinaberlautApp() {
    val navigationState = rememberNavigationState(
        startRoute = HomeRoute,
        topLevelRoutes = TOP_LEVEL_ROUTES,
    )
    val navigator = remember(navigationState) { Navigator(navigationState) }

    val entryProvider = entryProvider {
        entry<HomeRoute> { PlaceholderScreen(R.string.nav_home, R.string.placeholder_home) }
        entry<LibraryRoute> { PlaceholderScreen(R.string.nav_library, R.string.placeholder_library) }
        entry<SearchRoute> { PlaceholderScreen(R.string.nav_search, R.string.placeholder_search) }
        entry<DownloadsRoute> { PlaceholderScreen(R.string.nav_downloads, R.string.placeholder_downloads) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = destination.route == navigationState.topLevelRoute,
                        onClick = { navigator.navigate(destination.route) },
                        icon = { Icon(painterResource(destination.icon), contentDescription = null) },
                        label = { Text(stringResource(destination.label)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavDisplay(
            entries = navigationState.toDecoratedEntries(entryProvider),
            onBack = { navigator.goBack() },
            modifier = Modifier.padding(innerPadding),
        )
    }
}
