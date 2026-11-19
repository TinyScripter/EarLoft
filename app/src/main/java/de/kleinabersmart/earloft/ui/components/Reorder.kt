package de.kleinabersmart.earloft.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.kleinabersmart.earloft.R
import kotlin.math.abs
import kotlin.math.sign
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Reordering without dragging: TalkBack can't operate the drag handle, so the row offers "Move up" and "Move down"
 * as separate actions. [index] is its position among [count] rows.
 */
@Composable
fun Modifier.reorderActions(index: Int, count: Int, onMove: (from: Int, to: Int) -> Unit): Modifier {
    val up = stringResource(R.string.action_move_up)
    val down = stringResource(R.string.action_move_down)
    return semantics {
        customActions = buildList {
            if (index > 0) add(CustomAccessibilityAction(up) { onMove(index, index - 1); true })
            if (index in 0 until count - 1) add(CustomAccessibilityAction(down) { onMove(index, index + 1); true })
        }
    }
}

/**
 * Reordering via drag handle in a LazyColumn that consists only of the sortable rows (list position = row position).
 * The dragged row follows the finger, the others glide to their new place; after release the dragged
 * row slides into its gap. When it reaches the top or bottom edge, the list scrolls along, the closer to the edge, the faster.
 */
@Stable
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxScrollPxPerMs: Float,
) {
    /** Key of the row under the finger. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var dragOffset by mutableFloatStateOf(0f)

    // From the drag handle of the dragged row; edge auto-scrolling thus moves it even without finger movement.
    private var indexOfDragged: () -> Int = { -1 }
    private var move: (from: Int, to: Int) -> Unit = { _, _ -> }
    // Total finger movement since grabbing: auto-scroll only in the drag direction, not already when grabbing at the edge.
    private var dragTotal = 0f
    private var autoScroll: Job? = null

    // The released row while it is still sliding into its gap.
    private var settlingKey by mutableStateOf<Any?>(null)
    private val settleOffset = Animatable(0f)

    /** The row is lifted (dragged or still on its way into its gap) and needs its own background. */
    fun isLifted(key: Any): Boolean = key == draggedKey || key == settlingKey

    internal fun start(key: Any, index: () -> Int, onMove: (from: Int, to: Int) -> Unit) {
        // If the finger grabs the row while it is still sliding, continue from there instead of jumping.
        dragOffset = if (key == settlingKey) settleOffset.value else 0f
        if (key == settlingKey) settlingKey = null
        draggedKey = key
        indexOfDragged = index
        move = onMove
        dragTotal = 0f
        autoScroll?.cancel()
        autoScroll = scope.launch { scrollAtEdges() }
    }

    internal fun dragBy(delta: Float) {
        if (draggedKey == null) return
        dragOffset += delta
        dragTotal += delta
        moveIfPastNeighbour()
    }

    /** Once the dragged row is past the middle of its visible neighbor, the two swap places. */
    private fun moveIfPastNeighbour() {
        val key = draggedKey ?: return
        val from = indexOfDragged()
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.key == key }) return
        val to = if (dragOffset > 0) from + 1 else from - 1
        // Only swap with a visible neighbor; otherwise the dragged row would slide out of view and disappear.
        val neighbour = visible.firstOrNull { it.index == to } ?: return
        if (abs(dragOffset) <= neighbour.size / 2f) return
        // The list anchors its position to the key of the topmost visible row. If exactly that row moves, the list would
        // scroll after it and the dragged row would jump out of view; hence anchor to the position here.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        move(from, to)
        dragOffset -= sign(dragOffset) * neighbour.size
    }

    private suspend fun scrollAtEdges() {
        var last = withFrameMillis { it }
        while (true) {
            val now = withFrameMillis { it }
            val step = edgeScrollStep(now - last)
            last = now
            if (step != 0f) {
                // In small steps per frame: a swap pins the list and would cancel a scroll in progress.
                val scrolled = listState.scrollBy(step)
                // The row stays under the finger while the list keeps moving beneath it.
                dragOffset += scrolled
            }
            // Even without scrolling: after a large finger jump the row catches up frame by frame, one place at a time.
            moveIfPastNeighbour()
        }
    }

    private fun edgeScrollStep(elapsedMs: Long): Float {
        val key = draggedKey ?: return 0f
        val layout = listState.layoutInfo
        val item = layout.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        val top = item.offset + dragOffset
        val bottom = top + item.size
        val depth = when {
            dragTotal > 0 -> bottom - (layout.viewportEndOffset - edgePx)
            dragTotal < 0 -> layout.viewportStartOffset + edgePx - top
            else -> 0f
        }
        if (depth <= 0f) return 0f
        val speed = maxScrollPxPerMs * (depth / edgePx).coerceAtMost(1f)
        return sign(dragTotal) * speed * elapsedMs
    }

    internal fun release() {
        val key = draggedKey ?: return
        autoScroll?.cancel()
        autoScroll = null
        draggedKey = null
        settlingKey = key
        val offset = dragOffset
        dragOffset = 0f
        scope.launch {
            settleOffset.snapTo(offset)
            settleOffset.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow))
            if (settlingKey == key) settlingKey = null
        }
    }

    internal fun offsetOf(key: Any): Float = when (key) {
        draggedKey -> dragOffset
        settlingKey -> settleOffset.value
        else -> 0f
    }
}

@Composable
fun rememberReorderState(listState: LazyListState): ReorderState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(listState, density) {
        with(density) {
            // At the edge at most about one row every 70 ms.
            ReorderState(listState, scope, edgePx = 48.dp.toPx(), maxScrollPxPerMs = 1.dp.toPx())
        }
    }
}

/**
 * For the whole row [key]: the lifted row lies above the others and follows the finger; all others glide to their
 * new place when the order changes (also via [reorderActions] or on removal).
 */
fun Modifier.reorderableItem(itemScope: LazyItemScope, state: ReorderState, key: Any): Modifier =
    if (state.isLifted(key)) {
        zIndex(1f).graphicsLayer { translationY = state.offsetOf(key) }
    } else {
        // Without fade in/out: the music queue assigns fresh keys when it is rebuilt.
        with(itemScope) { animateItem(fadeInSpec = null, fadeOutSpec = null) }
    }

/**
 * For the drag handle of row [key]. [index] returns its current position, [onMove] moves it by one position;
 * [onDragEnd] reports after release whether it is now somewhere other than at the start.
 */
@Composable
fun Modifier.reorderHandle(
    state: ReorderState,
    key: Any,
    index: () -> Int,
    onMove: (from: Int, to: Int) -> Unit,
    onDragEnd: (moved: Boolean) -> Unit = {},
): Modifier {
    val currentIndex by rememberUpdatedState(index)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)
    return pointerInput(state, key) {
        var startIndex = -1
        fun finish() {
            state.release()
            currentOnDragEnd(currentIndex() != startIndex)
        }
        detectDragGestures(
            onDragStart = {
                startIndex = currentIndex()
                state.start(key, index = { currentIndex() }, onMove = { from, to -> currentOnMove(from, to) })
            },
            onDragEnd = ::finish,
            onDragCancel = ::finish,
        ) { change, drag ->
            change.consume()
            state.dragBy(drag.y)
        }
    }
}
