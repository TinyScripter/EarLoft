package de.kleinabersmart.earloft.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.core.common.normalizeForMerge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val THUMB_WIDTH = 6.dp
private val THUMB_HEIGHT = 56.dp
private val TOUCH_WIDTH = 40.dp
private val BUBBLE_SIZE = 56.dp
private const val HIDE_DELAY_MS = 1_500L

/**
 * Letter shown while fast scrolling: the first letter, otherwise "#" (digits, symbols).
 * [sortText] should be the text the list is sorted by.
 */
fun letterOf(sortText: String): String {
    val first = sortText.trim().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * Like [letterOf], but the way the book lists sort (`normalizeForMerge()`): without umlauts and accents, leading punctuation
 * skipped. "Ödön" is filed under O and shows "O".
 */
fun foldedLetterOf(sortText: String): String =
    sortText.normalizeForMerge().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"

/**
 * Fast scroller for an [androidx.compose.foundation.lazy.LazyColumn]; [labelAt] returns the label for a list index (e.g. the
 * letter). Without [labelAt] (lists by date or in a custom order) there is only the thumb, no bubble.
 * [onDragStart] reports the grab: jumping via `scrollToItem` doesn't count as dragging the list (e.g. for follow mode).
 */
@Composable
fun LazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    labelAt: ((Int) -> String)? = null,
    onDragStart: () -> Unit = {},
) {
    // layoutInfo changes on every scroll frame; only the count matters here.
    val itemCount by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    FastScroller(
        itemCount = itemCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
        onDragStart = onDragStart,
    )
}

/**
 * A [LazyColumn] with a [LazyListFastScroller] on top, for lists that share their frame with nothing else. [modifier]
 * applies to both; if the frame adapts to the content (bottom sheet), so does the thumb's track.
 */
@Composable
fun FastScrollLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    labelAt: ((Int) -> String)? = null,
    content: LazyListScope.() -> Unit,
) {
    Box(modifier, propagateMinConstraints = true) {
        LazyColumn(state = state, contentPadding = contentPadding, verticalArrangement = verticalArrangement, content = content)
        LazyListFastScroller(state, Modifier.matchParentSize(), labelAt)
    }
}

/** Like [LazyListFastScroller] for an [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]. */
@Composable
fun LazyGridFastScroller(state: LazyGridState, modifier: Modifier = Modifier, labelAt: ((Int) -> String)? = null) {
    val itemCount by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    FastScroller(
        itemCount = itemCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        visibleCount = { state.layoutInfo.visibleItemsInfo.size },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
    )
}

/**
 * Thumb on the right edge for quickly moving through long lists. It appears while scrolling
 * and fades out afterwards; while dragging, a bubble shows the label ([labelAt]) of the first visible row, none without a label.
 * Meant as an overlay above the list (same size), but only intercepts touches at the right edge.
 */
@Composable
private fun FastScroller(
    itemCount: Int,
    firstVisibleIndex: () -> Int,
    visibleCount: () -> Int,
    isScrolling: Boolean,
    scrollToItem: suspend (Int) -> Unit,
    labelAt: ((Int) -> String)?,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val currentCount by rememberUpdatedState(itemCount)
    val currentScrollTo by rememberUpdatedState(scrollToItem)
    val currentOnDragStart by rememberUpdatedState(onDragStart)
    val currentVisibleCount by rememberUpdatedState(visibleCount)

    LaunchedEffect(isScrolling, dragging) {
        if (isScrolling || dragging) {
            visible = true
        } else {
            delay(HIDE_DELAY_MS)
            visible = false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = with(density) { THUMB_HEIGHT.toPx() }
        val rangePx = (trackPx - thumbPx).coerceAtLeast(1f)

        // Read only during placement: scrolling then only moves the thumb without recomposing. At the end of the list the first
        // visible row is the one [lastFirstIndex] returns, so the thumb reaches the bottom also for short lists.
        val thumbTop = {
            val last = lastFirstIndex(itemCount, visibleCount())
            val listFraction = if (last > 0) firstVisibleIndex().coerceIn(0, last) / last.toFloat() else 0f
            ((if (dragging) dragFraction else listFraction) * rangePx).roundToInt()
        }

        AnimatedVisibility(
            visible = visible && itemCount > 1,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(
                modifier = Modifier
                    .width(TOUCH_WIDTH)
                    .height(maxHeight)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                currentOnDragStart()
                                dragging = true
                                dragFraction = ((offset.y - thumbPx / 2) / rangePx).coerceIn(0f, 1f)
                                scrollTo(scope, currentScrollTo, lastFirstIndex(currentCount, currentVisibleCount()), dragFraction)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                dragFraction = ((change.position.y - thumbPx / 2) / rangePx).coerceIn(0f, 1f)
                                scrollTo(scope, currentScrollTo, lastFirstIndex(currentCount, currentVisibleCount()), dragFraction)
                            },
                        )
                    }
                    // A tap on the visible strip jumps there (E16, review2 Z18) instead of being swallowed without effect.
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            currentOnDragStart()
                            val fraction = ((offset.y - thumbPx / 2) / rangePx).coerceIn(0f, 1f)
                            scrollTo(scope, currentScrollTo, lastFirstIndex(currentCount, currentVisibleCount()), fraction)
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbTop()) }
                        .padding(end = 4.dp)
                        .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(THUMB_WIDTH)),
                )
            }
        }

        if (labelAt != null) AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTop() + (thumbPx / 2 - with(density) { BUBBLE_SIZE.toPx() } / 2).roundToInt()) }
                .padding(end = TOUCH_WIDTH + 8.dp),
        ) {
            val labelIndex = if (dragging) (dragFraction * lastFirstIndex(itemCount, visibleCount())).roundToInt() else firstVisibleIndex()
            // One or two characters: circle; longer labels ("#100", years) turn it into a pill.
            Box(
                modifier = Modifier
                    .height(BUBBLE_SIZE)
                    .widthIn(min = BUBBLE_SIZE)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = labelAt(labelIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** [lastIndex]: the first visible row at the end of the list ([lastFirstIndex]). */
private fun scrollTo(
    scope: CoroutineScope,
    scrollToItem: suspend (Int) -> Unit,
    lastIndex: Int,
    fraction: Float,
) {
    if (lastIndex < 0) return
    val index = (fraction * lastIndex).roundToInt().coerceIn(0, lastIndex)
    scope.launch { scrollToItem(index) }
}

/**
 * The first visible row once the list is scrolled to its end: [count] minus the rows that fit, at least the last row while nothing is
 * laid out yet. Thumb position and jump both refer to it.
 */
internal fun lastFirstIndex(count: Int, visible: Int): Int = if (count <= 0) -1 else (count - visible.coerceAtLeast(1)).coerceAtLeast(0)
