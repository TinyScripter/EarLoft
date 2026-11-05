package keinabersmart.kleinaberlaut.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity

/**
 * Für horizontal scrollende Zeilen (Karussells, Chip-Reihen) in einem Pager: Wischt man über ihr Ende hinaus, bleibt
 * der Rest der Geste bei der Zeile, statt Reiter oder Bereich zu wechseln. Passt die Zeile ganz auf den Bildschirm,
 * lässt sie die Geste durch; dann wischt man dort wie daneben.
 */
fun Modifier.keepHorizontalScroll(state: ScrollableState): Modifier = nestedScroll(KeepHorizontalScroll(state))

private class KeepHorizontalScroll(private val state: ScrollableState) : NestedScrollConnection {
    private val scrollable get() = state.canScrollForward || state.canScrollBackward

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (scrollable) Offset(available.x, 0f) else Offset.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (scrollable) Velocity(available.x, 0f) else Velocity.Zero
}
