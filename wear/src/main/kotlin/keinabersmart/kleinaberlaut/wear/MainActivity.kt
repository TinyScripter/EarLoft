package keinabersmart.kleinaberlaut.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.navigation3.rememberSwipeDismissableSceneStrategy
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.wear.ui.SelectionScreen
import keinabersmart.kleinaberlaut.wear.ui.StartScreen
import kotlinx.serialization.Serializable

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            WearApp()
        }
    }
}

@Serializable
data object StartRoute : NavKey

/** Eine Auswahl vom Handy; [playlistId] ist ihre Geräte-Playlist auf der Uhr (Wear-Konzept 7.2). */
@Serializable
data class SelectionRoute(val playlistId: Long) : NavKey

@Composable
fun WearApp() {
    MaterialTheme {
        AppScaffold {
            val backStack = rememberNavBackStack(StartRoute)
            NavDisplay(
                backStack = backStack,
                sceneStrategies = listOf(rememberSwipeDismissableSceneStrategy()),
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<StartRoute> { StartScreen(onSelectionClick = { backStack.add(SelectionRoute(it)) }) }
                    entry<SelectionRoute> { route -> SelectionScreen(playlistId = route.playlistId) }
                },
            )
        }
    }
}
