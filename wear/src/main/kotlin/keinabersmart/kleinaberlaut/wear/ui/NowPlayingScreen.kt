package keinabersmart.kleinaberlaut.wear.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.semantics.contentDescription
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
import kotlinx.coroutines.delay

/**
 * Wiedergabe (Wear-Konzept 11): Cover, Titel, Steuerung und Fortschrittsring; die Krone ändert die Lautstärke.
 * Ohne passende Ausgabe (Kopfhörer) hält die Wiedergabe an und der Bluetooth-Knopf öffnet die Einstellungen.
 */
@Composable
fun NowPlayingScreen(viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val volumeVisible by viewModel.volumeVisible.collectAsStateWithLifecycle()
    val context = LocalContext.current
    NowPlayingContent(
        state = state,
        positionMs = positionMs,
        cover = cover,
        volume = volume,
        volumeVisible = volumeVisible,
        onPrevious = viewModel::previous,
        onPlayPause = viewModel::togglePlayPause,
        onNext = viewModel::next,
        onShuffle = viewModel::toggleShuffle,
        onOutput = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
        onCrown = viewModel::onCrown,
    )
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    positionMs: Long,
    cover: android.graphics.Bitmap?,
    volume: Float,
    volumeVisible: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onOutput: () -> Unit,
    onCrown: (Float) -> Unit,
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // Beim Bildschirmwechsel verliert das Feld den Fokus gleich wieder (die Liste davor wird abgebaut); ohne Fokus
    // kommt die Krone nie an. Deshalb nach jedem Verlust erneut anfordern.
    LaunchedEffect(hasFocus) {
        if (!hasFocus) {
            delay(FOCUS_RETRY_MS)
            focusRequester.requestFocus()
        }
    }
    ScreenScaffold {
        Box(
            Modifier
                .fillMaxSize()
                .onRotaryScrollEvent {
                    onCrown(it.verticalScrollPixels)
                    true
                }
                .focusRequester(focusRequester)
                .onFocusChanged { hasFocus = it.isFocused }
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
            if (volumeVisible) VolumeIndicator(volume)
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

private const val FOCUS_RETRY_MS = 150L
private val RING_STROKE = 5.dp
private const val RING_START_ANGLE = 295f
private const val RING_SWEEP = 310f

/** Lautstärkebalken in der Mitte, solange an der Krone gedreht wird. */
@Composable
private fun VolumeIndicator(volume: Float, modifier: Modifier = Modifier) {
    val percent = (volume * 100).toInt()
    val label = stringResource(R.string.player_volume)
    val value = stringResource(R.string.player_volume_percent, percent)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$label $value" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Box(
            Modifier
                .padding(top = 6.dp)
                .width(VOLUME_BAR_WIDTH)
                .height(VOLUME_BAR_HEIGHT)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(volume.coerceIn(0f, 1f))
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

private val VOLUME_BAR_WIDTH = 110.dp
private val VOLUME_BAR_HEIGHT = 8.dp

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
            volume = 0.4f,
            volumeVisible = true,
            onPrevious = {},
            onPlayPause = {},
            onNext = {},
            onShuffle = {},
            onOutput = {},
            onCrown = {},
        )
    }
}
