package keinabersmart.kleinaberlaut.wear.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
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
import kotlin.math.abs

/**
 * Wiedergabe (Wear-Konzept 11): Das Cover füllt, leicht unscharf, den Kreis innerhalb des Fortschrittsrings; darauf Titel und
 * Steuerung, Wiedergabe/Pause genau in der Mitte. Akzentfarben kommen aus dem Cover, die Krone ändert die Lautstärke.
 * Ohne passende Ausgabe (Kopfhörer) hält die Wiedergabe an; ein Tipp auf den Hinweis öffnet die Bluetooth-Einstellungen.
 */
@Composable
fun NowPlayingScreen(onOpenSleepTimer: () -> Unit = {}, viewModel: PlayerViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val positionMs by viewModel.positionMs.collectAsStateWithLifecycle()
    val cover by viewModel.cover.collectAsStateWithLifecycle()
    val volume by viewModel.volume.collectAsStateWithLifecycle()
    val volumeVisible by viewModel.volumeVisible.collectAsStateWithLifecycle()
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AlbumColorTheme(seedColor) {
        NowPlayingContent(
            state = state,
            positionMs = positionMs,
            cover = cover,
            volume = volume,
            volumeVisible = volumeVisible,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            // Läuft ein Timer, schaltet ein Tipp ihn aus; sonst öffnet er die Auswahl.
            onSleepTimer = { if (sleepTimerRemainingMs != null) viewModel.cancelSleepTimer() else onOpenSleepTimer() },
            onPrevious = viewModel::previous,
            onPlayPause = viewModel::togglePlayPause,
            onNext = viewModel::next,
            onShuffle = viewModel::toggleShuffle,
            onOutput = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onCrown = viewModel::onCrown,
            onSeek = viewModel::seekTo,
        )
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    positionMs: Long,
    cover: android.graphics.Bitmap?,
    volume: Float,
    volumeVisible: Boolean,
    sleepTimerRemainingMs: Long?,
    onSleepTimer: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onShuffle: () -> Unit,
    onOutput: () -> Unit,
    onCrown: (Float) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = state.durationMs
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    // Nach dem Loslassen bleibt die Anzeige am Ziel, bis der Player nachgekommen ist (die Position wird nur alle 500 ms gelesen).
    LaunchedEffect(seekTarget, positionMs) {
        seekTarget?.let { if (abs(positionMs - it) < SEEK_SETTLE_MS) seekTarget = null }
    }
    LaunchedEffect(seekTarget) {
        if (seekTarget != null) {
            delay(SEEK_HOLD_MAX_MS)
            seekTarget = null
        }
    }
    val shownPositionMs = dragProgress?.let { (it * duration).toLong() } ?: seekTarget ?: positionMs
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
            val progress = if (duration > 0) (shownPositionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
            val positionLabel = stringResource(R.string.player_position)
            CoverBackground(cover)
            // Vor der Steuerung gezeichnet: Berührungen auf den Knöpfen gehen an die Knöpfe, nicht an den Ring.
            ProgressRing(
                progress = progress,
                modifier = Modifier
                    .fillMaxSize()
                    .ringSeek(
                        enabled = duration > 0,
                        bandWidth = SEEK_BAND_WIDTH,
                        onPreview = { dragProgress = it },
                        onSeek = { target ->
                            val ms = (target * duration).toLong()
                            seekTarget = ms
                            onSeek(ms)
                        },
                    )
                    .semantics {
                        contentDescription = positionLabel
                        stateDescription = "${formatPosition(shownPositionMs)} / ${formatPosition(duration)}"
                        progressBarRangeInfo = ProgressBarRangeInfo(progress, 0f..1f)
                        setProgress { target ->
                            val ms = (target.coerceIn(0f, 1f) * duration).toLong()
                            seekTarget = ms
                            onSeek(ms)
                            true
                        }
                    },
            )
            val current = state.current
            Column(
                modifier = Modifier.fillMaxSize().padding(COVER_INSET),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Oben und unten gleich viel Platz (je weight 1): So sitzt die Steuerung, und mit ihr Wiedergabe/Pause, genau in der Mitte des Kreises.
                Column(
                    modifier = Modifier.weight(1f).padding(horizontal = TEXT_PADDING),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Text(
                        current?.title ?: stringResource(R.string.player_nothing),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.outputSuppressed) stringResource(R.string.player_no_output) else current?.artist.orEmpty(),
                        modifier = if (state.outputSuppressed) {
                            Modifier.clickable(onClickLabel = stringResource(R.string.player_output), onClick = onOutput)
                        } else {
                            Modifier
                        },
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING)) {
                    ControlButton(R.drawable.ic_skip_previous, R.string.player_previous, SKIP_BUTTON_SIZE, onPrevious)
                    ControlButton(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        PLAY_BUTTON_SIZE,
                        onPlayPause,
                        active = true,
                    )
                    ControlButton(R.drawable.ic_skip_next, R.string.player_next, SKIP_BUTTON_SIZE, onNext)
                }
                Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(CONTROL_SPACING)) {
                        ControlButton(
                            R.drawable.ic_shuffle,
                            R.string.player_shuffle,
                            SHUFFLE_BUTTON_SIZE,
                            onShuffle,
                            active = state.shuffle,
                            toggle = true,
                        )
                        ControlButton(
                            R.drawable.ic_bedtime,
                            R.string.sleep_timer_title,
                            SHUFFLE_BUTTON_SIZE,
                            onSleepTimer,
                            active = sleepTimerRemainingMs != null,
                        )
                    }
                    if (sleepTimerRemainingMs != null) {
                        Text(
                            stringResource(R.string.sleep_timer_badge, minutesLeft(sleepTimerRemainingMs)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (dragProgress != null) PositionIndicator(shownPositionMs, duration)
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

/** Das Cover als Hintergrund: groß, nur leicht unscharf, abgedunkelt und zum Rand hin weich nach Schwarz, mit Abstand zum Ring. */
@Composable
private fun CoverBackground(cover: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(COVER_INSET).clip(CircleShape)) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Unbounded: Sonst verblasst die Unschärfe an den Rändern des Bildes zu Transparenz.
                modifier = Modifier.fillMaxSize().blur(COVER_BLUR, BlurredEdgeTreatment.Unbounded),
            )
            // Abdunkeln: Titel und Knöpfe müssen auch auf hellen Covern lesbar bleiben.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = COVER_DIM)))
            // Nach außen weich zu Schwarz: Das Cover endet nicht an einer harten Kante, sondern geht in den schwarzen Hintergrund über.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.radialGradient(COVER_FADE_START to Color.Transparent, 1f to Color.Black),
                ),
            )
        } else {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow))
        }
    }
}

private const val FOCUS_RETRY_MS = 150L
private const val SEEK_SETTLE_MS = 1_500L
private const val SEEK_HOLD_MAX_MS = 2_000L
private val SEEK_BAND_WIDTH = 32.dp
private val RING_STROKE = 5.dp

/** Abstand des Covers vom Rand: Ring (5 dp) plus Luft, damit das Cover den Fortschrittsring nicht berührt. */
private val COVER_INSET = 14.dp
private val COVER_BLUR = 3.dp
private const val COVER_DIM = 0.3f

/** Ab diesem Anteil des Radius beginnt der Verlauf nach Schwarz (am Rand ist es ganz schwarz). */
private const val COVER_FADE_START = 0.6f
private val TEXT_PADDING = 12.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val SKIP_BUTTON_SIZE = 52.dp
private val SHUFFLE_BUTTON_SIZE = 44.dp
private val CONTROL_SPACING = 2.dp

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

/** Zeit beim Ziehen am Ring: „1:23 / 3:45“. */
@Composable
private fun PositionIndicator(positionMs: Long, durationMs: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("${formatPosition(positionMs)} / ${formatPosition(durationMs)}", style = MaterialTheme.typography.titleMedium)
    }
}

private val VOLUME_BAR_WIDTH = 110.dp
private val VOLUME_BAR_HEIGHT = 8.dp

/** [active]: in der Akzentfarbe gefüllt, sonst gedämpft auf dem Cover. */
@Composable
private fun ControlButton(
    iconRes: Int,
    descriptionRes: Int,
    size: Dp,
    onClick: () -> Unit,
    active: Boolean = false,
    toggle: Boolean = false,
) {
    IconButton(
        onClick = onClick,
        // Der aktive Zustand ist sonst nur an der Farbe zu erkennen; Screenreader bekommen ihn als „ausgewählt“.
        modifier = Modifier.size(size).then(if (toggle) Modifier.semantics { selected = active } else Modifier),
        colors = if (active) IconButtonDefaults.filledIconButtonColors() else IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = stringResource(descriptionRes),
            modifier = Modifier.size(IconButtonDefaults.iconSizeFor(size)),
        )
    }
}

/** Buntes Beispiel-Cover für die Vorschau (Verlauf mit einem hellen Fleck, wie echte Cover es oft haben). */
private fun previewCover(): android.graphics.Bitmap {
    val size = 200
    val bitmap = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(
            0f, 0f, size.toFloat(), size.toFloat(),
            intArrayOf(0xFFE53935.toInt(), 0xFFFFB300.toInt(), 0xFF3949AB.toInt()),
            null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
    canvas.drawCircle(size * 0.3f, size * 0.3f, size * 0.18f, android.graphics.Paint().apply { color = 0xFFFFF8E1.toInt() })
    return bitmap
}

@WearPreviewDevices
@Composable
private fun NowPlayingPreview() {
    MaterialTheme {
        AlbumColorTheme(seedColor = 0xFFE53935.toInt()) {
            NowPlayingContent(
                state = PlayerState(
                    current = QueueEntry(1, "Mondlicht", "Nachtfalter", 1),
                    isPlaying = true,
                    durationMs = 200_000,
                    repeatMode = Player.REPEAT_MODE_OFF,
                ),
                positionMs = 80_000,
                cover = previewCover(),
                volume = 0.4f,
                volumeVisible = false,
                sleepTimerRemainingMs = 754_000,
                onSleepTimer = {},
                onPrevious = {},
                onPlayPause = {},
                onNext = {},
                onShuffle = {},
                onOutput = {},
                onCrown = {},
                onSeek = {},
            )
        }
    }
}
