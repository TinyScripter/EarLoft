package de.kleinabersmart.kleinaberlaut.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
 * Buchstabe für die Anzeige beim Schnellscrollen: erster Buchstabe, sonst „#“ (Ziffern, Zeichen).
 * [sortText] sollte der Text sein, nach dem die Liste sortiert ist.
 */
fun letterOf(sortText: String): String {
    val first = sortText.trim().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/** Schnellscroller für eine [androidx.compose.foundation.lazy.LazyColumn]; [labelAt] liefert zum Listenindex den Buchstaben. */
@Composable
fun LazyListFastScroller(state: LazyListState, labelAt: (Int) -> String, modifier: Modifier = Modifier) {
    FastScroller(
        itemCount = state.layoutInfo.totalItemsCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
    )
}

/** Wie [LazyListFastScroller] für ein [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]. */
@Composable
fun LazyGridFastScroller(state: LazyGridState, labelAt: (Int) -> String, modifier: Modifier = Modifier) {
    FastScroller(
        itemCount = state.layoutInfo.totalItemsCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
    )
}

/**
 * Griff am rechten Rand, mit dem sich lange Listen schnell durchfahren lassen. Er erscheint beim Scrollen
 * und blendet sich danach aus; beim Ziehen zeigt eine Blase den Buchstaben der ersten sichtbaren Zeile.
 * Gedacht als Überlagerung über der Liste (gleiche Größe), fängt aber nur am rechten Rand Berührungen ab.
 */
@Composable
private fun FastScroller(
    itemCount: Int,
    firstVisibleIndex: () -> Int,
    isScrolling: Boolean,
    scrollToItem: suspend (Int) -> Unit,
    labelAt: (Int) -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var visible by remember { mutableStateOf(false) }
    val currentCount by rememberUpdatedState(itemCount)
    val currentScrollTo by rememberUpdatedState(scrollToItem)

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

        val listFraction = if (itemCount > 1) firstVisibleIndex().coerceIn(0, itemCount - 1) / (itemCount - 1f) else 0f
        val fraction = if (dragging) dragFraction else listFraction
        val thumbTop = (fraction * rangePx).roundToInt()
        val labelIndex = if (dragging) (dragFraction * (itemCount - 1)).roundToInt() else firstVisibleIndex()

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
                                dragging = true
                                dragFraction = ((offset.y - thumbPx / 2) / rangePx).coerceIn(0f, 1f)
                                scrollTo(scope, currentScrollTo, currentCount, dragFraction)
                            },
                            onDragEnd = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, _ ->
                                change.consume()
                                dragFraction = ((change.position.y - thumbPx / 2) / rangePx).coerceIn(0f, 1f)
                                scrollTo(scope, currentScrollTo, currentCount, dragFraction)
                            },
                        )
                    },
            ) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, thumbTop) }
                        .padding(end = 4.dp)
                        .size(width = THUMB_WIDTH, height = THUMB_HEIGHT)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(THUMB_WIDTH)),
                )
            }
        }

        AnimatedVisibility(
            visible = dragging,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, thumbTop + (thumbPx / 2 - with(density) { BUBBLE_SIZE.toPx() } / 2).roundToInt()) }
                .padding(end = TOUCH_WIDTH + 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(BUBBLE_SIZE)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
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

private fun scrollTo(
    scope: CoroutineScope,
    scrollToItem: suspend (Int) -> Unit,
    count: Int,
    fraction: Float,
) {
    if (count <= 0) return
    val index = (fraction * (count - 1)).roundToInt().coerceIn(0, count - 1)
    scope.launch { scrollToItem(index) }
}
