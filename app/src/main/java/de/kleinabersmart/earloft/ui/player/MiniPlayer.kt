package de.kleinabersmart.earloft.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun MiniPlayer(
    state: PlayerState,
    positionProvider: () -> Long,
    positionJumps: Flow<Unit>,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val position by rememberPlaybackPosition(state.isPlaying, positionJumps, current, positionProvider = positionProvider)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                AlbumArt(albumId = current.albumId, modifier = Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        painterResource(playPauseIcon(state.isPlaying)),
                        contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.action_next))
                }
            }
        }
    }
}

/** So weit (oder schnell genug) nach unten gewischt, verschwindet der Mini-Player; sonst springt er zurück. */
private val DISMISS_DISTANCE_DOWN = 48.dp

/** Zur Seite: dieser Anteil der Breite (Seitwärtswischen ist leichter versehentlich als nach unten). */
private const val DISMISS_FRACTION_SIDEWAYS = 0.3f
private const val DISMISS_VELOCITY = 1_500f

/**
 * Mini-Player nach unten, links oder rechts wegwischen (nur wenn [enabled], also pausiert und nicht beim Casten): Er folgt dem
 * Finger und blasst aus. Nach unten über [DISMISS_DISTANCE_DOWN], zur Seite über [DISMISS_FRACTION_SIDEWAYS] der Breite, oder
 * jeweils mit Schwung, ruft das Loslassen [onDismiss]; zur Seite gleitet er vorher ganz hinaus. Welche Richtung gilt, entscheidet
 * die erste Bewegung (zwei `draggable`, je eine Achse). Für TalkBack dieselbe Aktion als „Mini-Player ausblenden“ ([label]).
 */
@Composable
internal fun Modifier.swipeToDismiss(enabled: Boolean, label: String, onDismiss: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    var widthPx by remember { mutableFloatStateOf(0f) }
    val downPx = with(LocalDensity.current) { DISMISS_DISTANCE_DOWN.toPx() }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    val verticalState = rememberDraggableState { delta -> scope.launch { offsetY.snapTo((offsetY.value + delta).coerceAtLeast(0f)) } }
    val horizontalState = rememberDraggableState { delta -> scope.launch { offsetX.snapTo(offsetX.value + delta) } }
    return this
        .onSizeChanged { widthPx = it.width.toFloat() }
        .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
        .graphicsLayer {
            val sideways = if (widthPx > 0) abs(offsetX.value) / widthPx else 0f
            val down = offsetY.value / (downPx * 2)
            alpha = 1f - maxOf(sideways, down).coerceIn(0f, 0.6f)
        }
        .draggable(
            state = verticalState,
            orientation = Orientation.Vertical,
            enabled = enabled,
            // Weggewischt bleibt er, wo er ist: Das Ausblenden kommt einen Frame später an, ein Zurückspringen blitzte auf.
            // Kommt er wieder, entsteht er neu (mit Versatz 0).
            onDragStopped = { velocity ->
                if (offsetY.value >= downPx || velocity >= DISMISS_VELOCITY) currentOnDismiss() else offsetY.animateTo(0f)
            },
        )
        .draggable(
            state = horizontalState,
            orientation = Orientation.Horizontal,
            enabled = enabled,
            onDragStopped = { velocity ->
                val far = widthPx > 0 && abs(offsetX.value) >= widthPx * DISMISS_FRACTION_SIDEWAYS
                val fast = abs(velocity) >= DISMISS_VELOCITY
                if (far || fast) {
                    // In Wischrichtung ganz hinaus, dann weg; bei Schwung ohne Weg zählt die Richtung des Schwungs.
                    val direction = if (offsetX.value != 0f) sign(offsetX.value) else sign(velocity)
                    offsetX.animateTo(direction * widthPx, tween(150))
                    currentOnDismiss()
                } else {
                    offsetX.animateTo(0f)
                }
            },
        )
        .semantics {
            if (enabled) customActions = listOf(CustomAccessibilityAction(label) { currentOnDismiss(); true })
        }
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    EarLoftTheme {
        MiniPlayer(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "Ein Lied", artist = "Eine Band", albumId = null),
                isPlaying = true,
                durationMs = 200_000,
            ),
            positionProvider = { 80_000 },
            positionJumps = emptyFlow(),
            onClick = {},
            onTogglePlayPause = {},
            onNext = {},
        )
    }
}
