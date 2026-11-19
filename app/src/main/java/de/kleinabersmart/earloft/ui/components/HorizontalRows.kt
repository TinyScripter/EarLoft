package de.kleinabersmart.earloft.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * For horizontally scrolling rows (carousels, chip rows) inside a pager: swiping past their end keeps
 * the rest of the gesture with the row instead of switching tab or area. If the row fits entirely on screen,
 * it lets the gesture through; then swiping there behaves like next to it.
 */
fun Modifier.keepHorizontalScroll(state: ScrollableState): Modifier = nestedScroll(KeepHorizontalScroll(state))

private class KeepHorizontalScroll(private val state: ScrollableState) : NestedScrollConnection {
    private val scrollable get() = state.canScrollForward || state.canScrollBackward

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (scrollable) Offset(available.x, 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (scrollable) Velocity(available.x, 0f) else Velocity.Zero
}
