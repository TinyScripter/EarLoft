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
 * Umsortieren ohne Ziehen: Den Ziehgriff kann TalkBack nicht bedienen; die Zeile bietet deshalb „Nach oben“ und „Nach unten“
 * als eigene Aktionen an. [index] ist ihre Stelle unter [count] Zeilen.
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
 * Umsortieren per Ziehgriff in einer LazyColumn, die nur aus den sortierbaren Zeilen besteht (Listenstelle = Zeilenstelle).
 * Die gezogene Zeile folgt dem Finger, die übrigen gleiten an ihren neuen Platz; nach dem Loslassen rutscht die gezogene
 * Zeile in ihre Lücke. Kommt sie an den oberen oder unteren Rand, scrollt die Liste mit, je näher am Rand, desto schneller.
 */
@Stable
class ReorderState internal constructor(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val edgePx: Float,
    private val maxScrollPxPerMs: Float,
) {
    /** Kennung der Zeile unter dem Finger. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var dragOffset by mutableFloatStateOf(0f)

    // Vom Ziehgriff der gezogenen Zeile; das Mitscrollen am Rand verschiebt sie damit auch ohne Fingerbewegung.
    private var indexOfDragged: () -> Int = { -1 }
    private var move: (from: Int, to: Int) -> Unit = { _, _ -> }
    // Gesamte Fingerbewegung seit dem Greifen: Mitgescrollt wird nur in Zugrichtung, nicht schon beim Greifen am Rand.
    private var dragTotal = 0f
    private var autoScroll: Job? = null

    // Die losgelassene Zeile, solange sie noch in ihre Lücke rutscht.
    private var settlingKey by mutableStateOf<Any?>(null)
    private val settleOffset = Animatable(0f)

    /** Die Zeile ist angehoben (gezogen oder noch auf dem Weg in ihre Lücke) und braucht einen eigenen Hintergrund. */
    fun isLifted(key: Any): Boolean = key == draggedKey || key == settlingKey

    internal fun start(key: Any, index: () -> Int, onMove: (from: Int, to: Int) -> Unit) {
        // Greift der Finger die Zeile, während sie noch rutscht, geht es von dort weiter statt mit einem Sprung.
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

    /** Ist die gezogene Zeile über die Mitte ihrer sichtbaren Nachbarin hinaus, tauschen beide den Platz. */
    private fun moveIfPastNeighbour() {
        val key = draggedKey ?: return
        val from = indexOfDragged()
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.none { it.key == key }) return
        val to = if (dragOffset > 0) from + 1 else from - 1
        // Nur mit einer sichtbaren Nachbarin tauschen; sonst rutschte die gezogene Zeile aus dem Bild und verschwände.
        val neighbour = visible.firstOrNull { it.index == to } ?: return
        if (abs(dragOffset) <= neighbour.size / 2f) return
        // Die Liste hält ihre Position an der Kennung der obersten sichtbaren Zeile fest. Wandert genau diese, scrollte die
        // Liste ihr hinterher und die gezogene Zeile spränge aus dem Bild; deshalb hier an der Stelle festhalten.
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
                // In kleinen Schritten je Bild: Ein Platzwechsel hält die Liste fest und bräche ein laufendes Scrollen ab.
                val scrolled = listState.scrollBy(step)
                // Die Zeile bleibt unter dem Finger, während die Liste unter ihr weiterläuft.
                dragOffset += scrolled
            }
            // Auch ohne Scrollen: Nach einem großen Fingersprung rückt die Zeile Bild für Bild um je einen Platz nach.
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
            // Am Rand höchstens etwa eine Zeile alle 70 ms.
            ReorderState(listState, scope, edgePx = 48.dp.toPx(), maxScrollPxPerMs = 1.dp.toPx())
        }
    }
}

/**
 * Für die ganze Zeile [key]: Die angehobene Zeile liegt über den anderen und folgt dem Finger; alle anderen gleiten an ihren
 * neuen Platz, wenn sich die Reihenfolge ändert (auch über [reorderActions] oder beim Entfernen).
 */
fun Modifier.reorderableItem(itemScope: LazyItemScope, state: ReorderState, key: Any): Modifier =
    if (state.isLifted(key)) {
        zIndex(1f).graphicsLayer { translationY = state.offsetOf(key) }
    } else {
        // Ohne Ein- und Ausblenden: Die Warteschlange der Musik vergibt beim Neuaufbau frische Kennungen.
        with(itemScope) { animateItem(fadeInSpec = null, fadeOutSpec = null) }
    }

/**
 * Für den Ziehgriff der Zeile [key]. [index] liefert ihre aktuelle Stelle, [onMove] verschiebt sie um eine Stelle;
 * [onDragEnd] meldet nach dem Loslassen, ob sie jetzt woanders steht als zu Beginn.
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
