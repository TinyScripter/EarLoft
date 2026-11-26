package de.kleinabersmart.earloft.ui.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import kotlin.math.roundToInt

/** Thickness of the touch area of an [AlbumScrubber]. */
internal val SCRUBBER_THICKNESS = 40.dp
private val THUMB_LENGTH = 48.dp

/** Around the handle, excluded from the system's back gesture as well. */
private val GESTURE_MARGIN = 24.dp
private val BUBBLE_SIZE = 56.dp

/**
 * Slider through the albums of the record crate (vertical, right edge) and the carousel (horizontal, below), always visible:
 * dragging or tapping jumps to the album at that point (coarse; flipping or swiping is the fine control). While dragging, a
 * bubble shows [labelAt] (left of a vertical, above a horizontal slider, outside its bounds). For TalkBack an adjustable range,
 * one album per step. [position] may be fractional while the albums move.
 */
@Composable
internal fun AlbumScrubber(
    vertical: Boolean,
    count: Int,
    position: () -> Float,
    frontIndex: Int,
    frontTitle: String,
    labelAt: (Int) -> String,
    onSlide: (Boolean) -> Unit,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val last = (count - 1).coerceAtLeast(0)
    var dragging by remember { mutableStateOf(false) }
    var dragIndex by remember { mutableIntStateOf(0) }
    val currentLast by rememberUpdatedState(last)
    val currentJump by rememberUpdatedState(onJump)
    val currentSlide by rememberUpdatedState(onSlide)
    val state = stringResource(R.string.album_crate_state, frontIndex + 1, count, frontTitle)
    val title = stringResource(R.string.library_albums)

    BoxWithConstraints(
        modifier.semantics {
            contentDescription = title
            stateDescription = state
            progressBarRangeInfo = ProgressBarRangeInfo(frontIndex.toFloat(), 0f..last.toFloat(), steps = (last - 1).coerceAtLeast(0))
            setProgress { value ->
                currentJump(value.roundToInt())
                true
            }
        },
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { (if (vertical) maxHeight else maxWidth).toPx() }
        val thumbPx = with(density) { THUMB_LENGTH.toPx() }
        val bubblePx = with(density) { BUBBLE_SIZE.toPx() }
        val rangePx = (trackPx - thumbPx).coerceAtLeast(1f)
        // The pointer handlers below never restart, so the track length must be read when touched: it changes while the crate
        // is open (mini player slides in or out, the play row appears), review T8.
        val currentTrackPx by rememberUpdatedState(trackPx)
        val along = { offset: Offset -> if (vertical) offset.y else offset.x }
        val indexAt = { at: Float ->
            val range = (currentTrackPx - thumbPx).coerceAtLeast(1f)
            (((at - thumbPx / 2) / range).coerceIn(0f, 1f) * currentLast).roundToInt()
        }
        val thumbStart = { if (last > 0) ((position() / last).coerceIn(0f, 1f) * rangePx).roundToInt() else 0 }
        val start: (Offset) -> Unit = { offset ->
            dragging = true
            currentSlide(true)
            dragIndex = indexAt(along(offset))
            currentJump(dragIndex)
        }
        val end: () -> Unit = {
            dragging = false
            currentSlide(false)
        }
        val move: (PointerInputChange) -> Unit = { change ->
            change.consume()
            val index = indexAt(along(change.position))
            if (index != dragIndex) {
                dragIndex = index
                currentJump(index)
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(vertical) {
                    if (vertical) {
                        detectVerticalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, _ -> move(change) }
                    } else {
                        detectHorizontalDragGestures(onDragStart = start, onDragEnd = end, onDragCancel = end) { change, _ -> move(change) }
                    }
                }
                .pointerInput(vertical) { detectTapGestures { offset -> currentJump(indexAt(along(offset))) } },
        ) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .then(if (vertical) Modifier.width(4.dp).fillMaxHeight() else Modifier.height(4.dp).fillMaxWidth())
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(2.dp)),
            )
            Box(
                Modifier
                    .align(if (vertical) Alignment.TopCenter else Alignment.CenterStart)
                    .offset { if (vertical) IntOffset(0, thumbStart()) else IntOffset(thumbStart(), 0) }
                    .then(if (vertical) Modifier.size(width = 8.dp, height = THUMB_LENGTH) else Modifier.size(width = THUMB_LENGTH, height = 8.dp))
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)),
            )
            // At the screen's edge the system's back gesture would take a drag on the handle (review4 T55). Only around the handle:
            // the system honors at most 200 dp per edge.
            if (vertical) Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .offset { IntOffset(0, thumbStart() - GESTURE_MARGIN.roundToPx()) }
                    .fillMaxWidth()
                    .height(THUMB_LENGTH + GESTURE_MARGIN * 2)
                    .systemGestureExclusion(),
            )
        }
        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(if (vertical) Alignment.TopEnd else Alignment.TopStart)
                .wrapContentSize(if (vertical) Alignment.TopEnd else Alignment.BottomStart, unbounded = true)
                .offset {
                    val gap = with(density) { (SCRUBBER_THICKNESS + 4.dp).roundToPx() }
                    val centered = thumbStart() + ((thumbPx - bubblePx) / 2).roundToInt()
                    if (vertical) IntOffset(-gap, centered) else IntOffset(centered, -gap)
                },
        ) {
            Box(
                Modifier
                    .height(BUBBLE_SIZE)
                    .widthIn(min = BUBBLE_SIZE)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    labelAt(dragIndex.coerceIn(0, last)),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}
