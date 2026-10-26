package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR
import kotlinx.coroutines.delay

/** Fragt die Position regelmäßig ab, solange der Aufrufer sichtbar ist. */
@Composable
fun rememberPlaybackPosition(positionProvider: () -> Long): State<Long> {
    val provider by rememberUpdatedState(positionProvider)
    return produceState(initialValue = positionProvider()) {
        while (true) {
            value = provider()
            delay(POSITION_POLL_MS)
        }
    }
}

internal fun playPauseIcon(isPlaying: Boolean): Int =
    if (isPlaying) DesignR.drawable.ic_pause else DesignR.drawable.ic_play

internal fun playPauseLabel(isPlaying: Boolean): Int =
    if (isPlaying) R.string.action_pause else R.string.action_play

private const val POSITION_POLL_MS = 250L
