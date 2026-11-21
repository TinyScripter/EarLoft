package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.animation.core.animate
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.lerp
import kotlin.math.roundToInt

/**
 * Collapse state of the subscription page header: [fraction] runs from 0 (full size) to 1 (compact). The header follows the
 * finger: scrolling the episodes down first shrinks it, scrolling back up only grows it once the list is at its start (the
 * remaining downward pull then goes to pull-to-refresh). Released halfway, it settles to the nearer end ([settle]).
 *
 * The header loses exactly [rangePx] pixels between the two ends, so the list moves 1:1 with the finger while it shrinks:
 * [fixedRangePx] (cover) plus [collapsibleRangePx] (notices, measured by [collapsingBlock]).
 */
@Stable
internal class FeedHeaderCollapse(initialFraction: Float = 0f) : NestedScrollConnection {
    var fraction by mutableFloatStateOf(initialFraction)
        private set
    var fixedRangePx by mutableFloatStateOf(0f)
    var collapsibleRangePx by mutableFloatStateOf(0f)
    private val rangePx get() = fixedRangePx + collapsibleRangePx

    /** Moves the header by [deltaY] pixels (negative = shrink); returns the part it consumed. */
    fun scrollBy(deltaY: Float): Float {
        val range = rangePx
        if (range <= 0f) return 0f
        val before = fraction
        val target = before - deltaY / range
        fraction = target.coerceIn(0f, 1f)
        // Within the range all of it: a rounding remainder would otherwise reach pull-to-refresh and show its indicator.
        return if (fraction == target) deltaY else -(fraction - before) * range
    }

    /** Animates a header left between the two ends to the nearer one. */
    suspend fun settle() {
        val start = fraction
        if (start <= 0f || start >= 1f) return
        animate(start, if (start < 0.5f) 0f else 1f) { value, _ -> fraction = value }
    }

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (available.y < 0f) Offset(0f, scrollBy(available.y)) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (available.y > 0f) Offset(0f, scrollBy(available.y)) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
        settle()
        return Velocity.Zero
    }

    companion object {
        val Saver = Saver<FeedHeaderCollapse, Float>(save = { it.fraction }, restore = { FeedHeaderCollapse(it) })
    }
}

/** Square between [expanded] and [collapsed] side length; read during layout, so following the finger doesn't recompose. */
internal fun Modifier.collapsingSquare(expanded: Dp, collapsed: Dp, fraction: () -> Float): Modifier = layout { measurable, _ ->
    val side = lerp(expanded, collapsed, fraction()).roundToPx()
    val placeable = measurable.measure(Constraints.fixed(side, side))
    layout(side, side) { placeable.place(0, 0) }
}

/** Height between [expanded] and [collapsed], width as offered. */
internal fun Modifier.collapsingHeight(expanded: Dp, collapsed: Dp, fraction: () -> Float): Modifier = layout { measurable, constraints ->
    val height = lerp(expanded, collapsed, fraction()).roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = height, maxHeight = height))
    layout(placeable.width, height) { placeable.place(0, 0) }
}

/**
 * Block that shrinks to nothing from the bottom up while [fraction] goes to 1 and fades out [fadeSpeed] times as fast. Its
 * full height goes to [onFullHeight] (for [FeedHeaderCollapse.collapsibleRangePx]).
 */
internal fun Modifier.collapsingBlock(
    fraction: () -> Float,
    fadeSpeed: Float = 1f,
    onFullHeight: (Int) -> Unit = {},
): Modifier = graphicsLayer { alpha = (1f - fraction() * fadeSpeed).coerceIn(0f, 1f) }
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(minHeight = 0))
        onFullHeight(placeable.height)
        val shown = (placeable.height * (1f - fraction())).roundToInt().coerceAtLeast(0)
        layout(placeable.width, shown) { placeable.place(0, 0) }
    }
