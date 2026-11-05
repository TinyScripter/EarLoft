package de.kleinabersmart.tinyamp.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Position für Fortschrittsanzeigen. Regelmäßig abgefragt wird nur, solange [isPlaying] und der Aufrufer mindestens
 * STARTED ist – Compose hält Effekte bei gestoppter Activity nicht an, und der Mini-Player ist fast immer da. Pausiert
 * einmal, bei jedem Sprung ([jumps], z. B. Spulen von außen) und wenn sich [keys] ändern (anderer Titel, Zeitachse geladen).
 */
@Composable
fun rememberPlaybackPosition(
    isPlaying: Boolean,
    jumps: Flow<Unit>,
    vararg keys: Any?,
    positionProvider: () -> Long,
): State<Long> {
    val provider by rememberUpdatedState(positionProvider)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    return produceState(initialValue = positionProvider(), isPlaying, jumps, lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = provider()
            if (isPlaying) {
                while (true) {
                    delay(POSITION_POLL_MS)
                    value = provider()
                }
            } else {
                jumps.collect { value = provider() }
            }
        }
    }
}

internal fun playPauseIcon(isPlaying: Boolean): Int =
    if (isPlaying) DesignR.drawable.ic_pause else DesignR.drawable.ic_play

internal fun playPauseLabel(isPlaying: Boolean): Int =
    if (isPlaying) R.string.action_pause else R.string.action_play

private const val POSITION_POLL_MS = 250L
