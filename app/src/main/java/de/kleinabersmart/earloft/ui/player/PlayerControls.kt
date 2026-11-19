package de.kleinabersmart.earloft.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Position for progress indicators. Polled regularly only while [isPlaying] and the caller is at least
 * STARTED – Compose doesn't pause effects when the activity is stopped, and the mini player is almost always there. When paused,
 * once, on every seek ([jumps], e.g. seeking from outside) and when [keys] change (different title, timeline loaded).
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
