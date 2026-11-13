package de.kleinabersmart.earloft.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyItemScope
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import de.kleinabersmart.earloft.R
import kotlinx.coroutines.CoroutineScope
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
 * Zeile in ihre Lücke.
 */
@Stable
class ReorderState internal constructor(private val listState: LazyListState, private val scope: CoroutineScope) {
    /** Kennung der Zeile unter dem Finger. */
    var draggedKey by mutableStateOf<Any?>(null)
        private set
    private var dragOffset by mutableFloatStateOf(0f)

    // Die losgelassene Zeile, solange sie noch in ihre Lücke rutscht.
    private var settlingKey by mutableStateOf<Any?>(null)
    private val settleOffset = Animatable(0f)

    /** Die Zeile ist angehoben (gezogen oder noch auf dem Weg in ihre Lücke) und braucht einen eigenen Hintergrund. */
    fun isLifted(key: Any): Boolean = key == draggedKey || key == settlingKey

    internal fun start(key: Any) {
        // Greift der Finger die Zeile, während sie noch rutscht, geht es von dort weiter statt mit einem Sprung.
        dragOffset = if (key == settlingKey) settleOffset.value else 0f
        if (key == settlingKey) settlingKey = null
        draggedKey = key
    }

    internal fun dragBy(delta: Float, from: Int, onMove: (from: Int, to: Int) -> Unit) {
        val key = draggedKey ?: return
        dragOffset += delta
        val height = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }?.size?.toFloat() ?: return
        val to = when {
            dragOffset > height / 2 -> from + 1
            dragOffset < -height / 2 -> from - 1
            else -> from
        }
        if (to == from || to !in 0 until listState.layoutInfo.totalItemsCount) return
        // Die Liste hält ihre Position an der Kennung der obersten sichtbaren Zeile fest. Wandert genau diese, scrollte die
        // Liste ihr hinterher und die gezogene Zeile spränge aus dem Bild; deshalb hier an der Stelle festhalten.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        onMove(from, to)
        dragOffset -= (to - from) * height
    }

    internal fun release() {
        val key = draggedKey ?: return
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
    return remember(listState) { ReorderState(listState, scope) }
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
                state.start(key)
            },
            onDragEnd = ::finish,
            onDragCancel = ::finish,
        ) { change, drag ->
            change.consume()
            state.dragBy(drag.y, currentIndex(), currentOnMove)
        }
    }
}
