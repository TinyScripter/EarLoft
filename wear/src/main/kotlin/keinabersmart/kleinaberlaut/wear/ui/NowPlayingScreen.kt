package keinabersmart.kleinaberlaut.wear.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.wear.R
import kotlin.math.sign

/**
 * Wiedergabe (Wear-Konzept 11): Cover, Titel, Steuerung und Fortschrittsring; die Krone ändert die Lautstärke.
 * Ohne passende Ausgabe (Kopfhörer) hält die Wiedergabe an und der Bluetooth-Knopf öffnet die Einstellungen.
 */
@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val context = LocalContext.current
    NowPlayingContent(
        state = state,
        positionMs = positionMs,
        cover = cover,
        onPrevious = viewModel::previous,
        onPlayPause = viewModel::togglePlayPause,
        onNext = viewModel::next,
        onShuffle = viewModel::toggleShuffle,
        onOutput = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onVolume = viewModel::changeVolume,
    )
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    positionMs: Long,
    cover: android.graphics.Bitmap?,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onOutput: () -> Unit,
    onVolume: (Int) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    // Ein Schritt je Rastung; die Richtung gibt die Drehung vor.
                    onVolume(it.verticalScrollPixels.sign.toInt())
                    true
                }
                .focusRequester(focusRequester)
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            val duration = state.durationMs
            ProgressRing(
                progress = if (duration > 0) (positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                modifier = Modifier.fillMaxSize(),
            )
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                if (cover != null) {
                    Image(
                        bitmap = cover.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(44.dp).clip(CircleShape),
                    )
                }
                val current = state.current
                Text(
                    current?.title ?: stringResource(R.string.player_nothing),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    if (state.outputSuppressed) stringResource(R.string.player_no_output) else current?.artist.orEmpty(),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(R.drawable.ic_skip_previous, R.string.player_previous, onPrevious)
                    ControlButton(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        onPlayPause,
                    )
                    ControlButton(R.drawable.ic_skip_next, R.string.player_next, onNext)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ControlButton(R.drawable.ic_shuffle, R.string.player_shuffle, onShuffle, active = state.shuffle, toggle = true)
                    ControlButton(R.drawable.ic_bluetooth, R.string.player_output, onOutput, active = state.outputSuppressed)
                }
            }
        }
    }
}

/** Ring um den Rand mit Lücke oben (Wear-Konzept 11); [progress] füllt ihn im Uhrzeigersinn. */
@Composable
private fun ProgressRing(progress: Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val indicatorColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = RING_STROKE.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(trackColor, RING_START_ANGLE, RING_SWEEP, false, topLeft, arcSize, style = style)
        if (progress > 0f) {
            drawArc(indicatorColor, RING_START_ANGLE, RING_SWEEP * progress, false, topLeft, arcSize, style = style)
        }
    }
}

private val RING_STROKE = 5.dp
private const val RING_START_ANGLE = 295f
private const val RING_SWEEP = 310f

@Composable
private fun ControlButton(iconRes: Int, descriptionRes: Int, onClick: () -> Unit, active: Boolean = false, toggle: Boolean = false) {
    IconButton(
        onClick = onClick,
        // Der aktive Zustand ist sonst nur an der Farbe zu erkennen; Screenreader bekommen ihn als „ausgewählt“.
        modifier = Modifier.size(IconButtonDefaults.SmallButtonSize).then(if (toggle) Modifier.semantics { selected = active } else Modifier),
        colors = if (active) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.iconButtonColors(),
    ) {
        Icon(painterResource(iconRes), contentDescription = stringResource(descriptionRes))
    }
}

@WearPreviewDevices
@Composable
private fun NowPlayingPreview() {
    MaterialTheme {
        NowPlayingContent(
            state = PlayerState(
                current = QueueEntry(1, "Mondlicht", "Nachtfalter", 1),
                isPlaying = true,
                durationMs = 200_000,
                repeatMode = Player.REPEAT_MODE_OFF,
            ),
            positionMs = 80_000,
            cover = null,
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onShuffle = {},
            onOutput = {},
            onVolume = {},
        )
    }
}
