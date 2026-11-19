package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import kotlin.math.abs
import kotlin.math.atan2

/** The progress ring starts at 295° (Compose: 0° = three o'clock, clockwise) and leaves a gap at the top. */
internal const val RING_START_ANGLE = 295f
internal const val RING_SWEEP = 310f

/**
 * Position on the ring (0..1) for a touch point with the angle [angleDegrees] (as with `drawArc`).
 * Depending on [previous], the gap at the top belongs to the start or to the end: whoever is dragging at 90 % and gets into the gap
 * stays at the end instead of jumping to the start (and vice versa).
 */
internal fun ringProgress(angleDegrees: Float, previous: Float? = null): Float {
    val raw = (((angleDegrees - RING_START_ANGLE) % 360f) + 360f) % 360f / RING_SWEEP
    val gapEnd = 360f / RING_SWEEP
    if (previous != null) {
        // A jump over half the ring length is crossing the gap, not a real movement.
        if (raw > 1f || abs(raw - previous) > 0.5f) return if (previous >= 0.5f) 1f else 0f
        return raw
    }
    return when {
        raw <= 1f -> raw
        raw < (1f + gapEnd) / 2f -> 1f
        else -> 0f
    }
}

/** "1:05" or "1:02:05". */
internal fun formatPosition(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = totalSeconds % 3600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/**
 * Seeking at the edge of the now playing view: tapping the ring seeks to the position ([onSeek]), dragging along the ring reports
 * the position continuously via [onPreview] (`null` at the end) and seeks on release ([onSeek]).
 *
 * The gesture ignores touches in the inner area (buttons). Likewise movements across the ring: at the left edge that is the
 * "back" swipe gesture, which should not be blocked by it. Only a movement along the ring belongs to the gesture.
 */
@Composable
internal fun Modifier.ringSeek(
    enabled: Boolean,
    bandWidth: Dp,
    onPreview: (Float?) -> Unit,
    onSeek: (Float) -> Unit,
): Modifier {
    val preview by rememberUpdatedState(onPreview)
    val seek by rememberUpdatedState(onSeek)
    return pointerInput(enabled) {
        if (!enabled) return@pointerInput
        val slop = viewConfiguration.touchSlop
        val band = bandWidth.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            val center = Offset(size.width / 2f, size.height / 2f)
            val offset = down.position - center
            val distance = offset.getDistance()
            if (distance < size.width / 2f - band) return@awaitEachGesture
            val radial = offset / distance
            val tangent = Offset(-radial.y, radial.x)
            var progress = ringProgress(angleOf(offset))
            var dragging = false
            try {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
                    if (change.isConsumed) return@awaitEachGesture
                    if (change.changedToUp()) {
                        seek(progress)
                        return@awaitEachGesture
                    }
                    if (!dragging) {
                        val moved = change.position - down.position
                        if (moved.getDistance() <= slop) continue
                        val along = abs(moved.x * tangent.x + moved.y * tangent.y)
                        val across = abs(moved.x * radial.x + moved.y * radial.y)
                        if (along <= across) return@awaitEachGesture
                        dragging = true
                    }
                    progress = ringProgress(angleOf(change.position - center), progress)
                    preview(progress)
                    change.consume()
                }
            } finally {
                if (dragging) preview(null)
            }
        }
    }
}

private fun angleOf(fromCenter: Offset): Float {
    val degrees = Math.toDegrees(atan2(fromCenter.y, fromCenter.x).toDouble()).toFloat()
    return if (degrees < 0f) degrees + 360f else degrees
}
