package de.kleinabersmart.earloft.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Platz für Knöpfe eines Bereichs im Kopf der App, links von der Lupe (z. B. die Sortierung der Bibliothek). Jeder Bereich
 * (Musik, Podcasts, Bücher) hat seinen eigenen; gezeigt wird der des gewählten Bereichs.
 */
class HeaderActions {
    var content: (@Composable () -> Unit)? by mutableStateOf(null)
        private set

    internal fun set(content: (@Composable () -> Unit)?) {
        this.content = content
    }
}

val LocalHeaderActions = staticCompositionLocalOf<HeaderActions?> { null }

/** Zeigt [content] im Kopf, solange dieser Aufruf in der Komposition ist. */
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
