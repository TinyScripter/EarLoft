package de.kleinabersmart.earloft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Slot for an area's buttons in the app header, left of the magnifier (e.g. the library sort). Each area
 * (music, podcasts, books) has its own; the one of the selected area is shown.
 */
class HeaderActions {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    internal fun set(content: (@Composable () -> Unit)?) {
        this.content = content
    }
}

val LocalHeaderActions = staticCompositionLocalOf<HeaderActions?> { null }

/** Shows [content] in the header as long as this call is in the composition. */
@Composable
fun HeaderAction(content: @Composable () -> Unit) {
    val slot = LocalHeaderActions.current ?: return
    val current by rememberUpdatedState(content)
    DisposableEffect(slot) {
        val shown: @Composable () -> Unit = { current() }
        slot.set(shown)
        onDispose { if (slot.content === shown) slot.set(null) }
    }
}
