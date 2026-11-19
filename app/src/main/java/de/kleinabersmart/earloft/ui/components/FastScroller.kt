package de.kleinabersmart.earloft.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
 * Buchstabe für die Anzeige beim Schnellscrollen: erster Buchstabe, sonst „#“ (Ziffern, Zeichen).
 * [sortText] sollte der Text sein, nach dem die Liste sortiert ist.
 */
fun letterOf(sortText: String): String {
    val first = sortText.trim().firstOrNull() ?: return "#"
    return if (first.isLetter()) first.uppercaseChar().toString() else "#"
}

/**
 * Wie [letterOf], aber so, wie die Bücherlisten sortieren (`normalizeForMerge()`): ohne Umlaute und Akzente, Satzzeichen vorn
 * übergangen. „Ödön“ steht unter O und zeigt „O“.
 */
fun foldedLetterOf(sortText: String): String =
    sortText.normalizeForMerge().firstOrNull()?.takeIf { it.isLetter() }?.uppercaseChar()?.toString() ?: "#"

/**
 * Schnellscroller für eine [androidx.compose.foundation.lazy.LazyColumn]; [labelAt] liefert zum Listenindex die Marke (z. B. den
 * Buchstaben). Ohne [labelAt] (Listen nach Datum oder in eigener Reihenfolge) gibt es nur den Griff, keine Blase.
 * [onDragStart] meldet das Greifen: Das Springen per `scrollToItem` zählt nicht als Ziehen der Liste (z. B. für ein Mitlaufen).
 */
@Composable
fun LazyListFastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    labelAt: ((Int) -> String)? = null,
    onDragStart: () -> Unit = {},
) {
    // layoutInfo ändert sich bei jedem Scroll-Frame; nur die Anzahl ist hier von Belang.
    val itemCount by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    FastScroller(
        itemCount = itemCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
        onDragStart = onDragStart,
    )
}

/**
 * Eine [LazyColumn] mit [LazyListFastScroller] darüber, für Listen, bei denen nichts anderes im selben Rahmen liegt. [modifier]
 * gilt für beide; passt sich der Rahmen dem Inhalt an (Bottom Sheet), tut das auch die Spur des Griffs.
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

/** Wie [LazyListFastScroller] für ein [androidx.compose.foundation.lazy.grid.LazyVerticalGrid]. */
@Composable
fun LazyGridFastScroller(state: LazyGridState, modifier: Modifier = Modifier, labelAt: ((Int) -> String)? = null) {
    val itemCount by remember(state) { derivedStateOf { state.layoutInfo.totalItemsCount } }
    FastScroller(
        itemCount = itemCount,
        firstVisibleIndex = { state.firstVisibleItemIndex },
        isScrolling = state.isScrollInProgress,
        scrollToItem = { state.scrollToItem(it) },
        labelAt = labelAt,
        modifier = modifier,
    )
}

/**
 * Griff am rechten Rand, mit dem sich lange Listen schnell durchfahren lassen. Er erscheint beim Scrollen
 * und blendet sich danach aus; beim Ziehen zeigt eine Blase die Marke ([labelAt]) der ersten sichtbaren Zeile, ohne Marke keine.
 * Gedacht als Überlagerung über der Liste (gleiche Größe), fängt aber nur am rechten Rand Berührungen ab.
 */
@Composable
private fun FastScroller(
    itemCount: Int,
    firstVisibleIndex: () -> Int,
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

        // Erst beim Platzieren gelesen: Scrollen verschiebt dann nur den Griff, ohne neu zusammenzusetzen.
        val thumbTop = {
            val listFraction = if (itemCount > 1) firstVisibleIndex().coerceIn(0, itemCount - 1) / (itemCount - 1f) else 0f
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
            val labelIndex = if (dragging) (dragFraction * (itemCount - 1)).roundToInt() else firstVisibleIndex()
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
