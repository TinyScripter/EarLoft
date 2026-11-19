package de.kleinabersmart.earloft.ui.player

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun MiniPlayer(
    state: PlayerState,
    positionProvider: () -> Long,
    positionJumps: Flow<Unit>,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val position by rememberPlaybackPosition(state.isPlaying, positionJumps, current, positionProvider = positionProvider)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                AlbumArt(albumId = current.albumId, modifier = Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        painterResource(playPauseIcon(state.isPlaying)),
                        contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.action_next))
                }
            }
        }
    }
}

/** Swiped this far down (or fast enough), the mini player disappears; otherwise it springs back. */
private val DISMISS_DISTANCE_DOWN = 48.dp

/** Sideways: this fraction of the width (sideways swipes happen accidentally more easily than downward ones). */
private const val DISMISS_FRACTION_SIDEWAYS = 0.3f
private const val DISMISS_VELOCITY = 1_500f

/**
 * Swipe the mini player away down, left or right (only if [enabled], i.e. paused and not casting): it follows the
 * finger and fades out. Down past [DISMISS_DISTANCE_DOWN], sideways past [DISMISS_FRACTION_SIDEWAYS] of the width, or
 * with a fling in either case, releasing calls [onDismiss]; sideways it first glides all the way out. Which direction applies is decided by
 * the first movement (two `draggable`s, one per axis). For TalkBack the same action as "Hide mini player" ([label]).
 *
 * The offset follows the finger synchronously (plain state, no coroutine per movement): with `Animatable.snapTo` per delta
 * a straggling `snapTo` could cancel the animation on release, and the mini player stayed stuck halfway out.
 */
@Composable
internal fun Modifier.swipeToDismiss(enabled: Boolean, label: String, onDismiss: () -> Unit): Modifier {
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val downPx = with(LocalDensity.current) { DISMISS_DISTANCE_DOWN.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    // If playback starts mid-swipe, Compose cancels the drag and still calls onDragStopped: then it springs back.
    val currentEnabled by rememberUpdatedState(enabled)
    val verticalState = rememberDraggableState { delta -> offsetY = (offsetY + delta).coerceAtLeast(0f) }
    val horizontalState = rememberDraggableState { delta -> offsetX += delta }
    return this
        .onSizeChanged { widthPx = it.width.toFloat() }
        .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
        .graphicsLayer {
            val sideways = if (widthPx > 0) abs(offsetX) / widthPx else 0f
            val down = offsetY / (downPx * 2)
            alpha = 1f - maxOf(sideways, down).coerceIn(0f, 0.6f)
        }
        .draggable(
            state = verticalState,
            orientation = Orientation.Vertical,
            enabled = enabled,
            // Once swiped away it stays where it is: hiding arrives a frame later, springing back would flash.
            // When it comes back, it is created anew (with offset 0).
            onDragStopped = { velocity ->
                if (currentEnabled && (offsetY >= downPx || velocity >= DISMISS_VELOCITY)) {
                    currentOnDismiss()
                } else {
                    animate(offsetY, 0f) { value, _ -> offsetY = value }
                }
            },
        )
        .draggable(
            state = horizontalState,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStopped = { velocity ->
                val far = widthPx > 0 && abs(offsetX) >= widthPx * DISMISS_FRACTION_SIDEWAYS
                val fast = abs(velocity) >= DISMISS_VELOCITY
                if (currentEnabled && (far || fast)) {
                    // All the way out in the swipe direction, then gone; for a fling without distance, the fling direction counts. It's also
                    // gone if the animation is cancelled (new grab).
                    val direction = if (offsetX != 0f) sign(offsetX) else sign(velocity)
                    try {
                        animate(offsetX, direction * widthPx, animationSpec = tween(150)) { value, _ -> offsetX = value }
                    } finally {
                        if (currentEnabled) currentOnDismiss() else offsetX = 0f
                    }
                } else {
                    animate(offsetX, 0f) { value, _ -> offsetX = value }
                }
            },
        )
        .semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(label) { currentOnDismiss(); true })
        }
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    EarLoftTheme {
        MiniPlayer(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "A Song", artist = "A Band", albumId = null),
                isPlaying = true,
                durationMs = 200_000,
            ),
            positionProvider = { 80_000 },
            positionJumps = emptyFlow(),
            onClick = {},
            onTogglePlayPause = {},
            onNext = {},
        )
    }
}
