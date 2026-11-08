package de.kleinabersmart.earloft.wear.ui

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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
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
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Wiedergabe (Wear-Konzept 11): Das Cover füllt, leicht unscharf, den Kreis innerhalb des Fortschrittsrings; darauf Titel und
 * Steuerung, Wiedergabe/Pause genau in der Mitte. Akzentfarben kommen aus dem Cover, die Krone ändert die Lautstärke.
 * Ohne passende Ausgabe (Kopfhörer) hält die Wiedergabe an; ein Tipp auf den Hinweis öffnet die Bluetooth-Einstellungen.
 * Bei einem Buch oder einer Folge springen die Knöpfe neben Wiedergabe/Pause −30 s / +10 s, Zufall gibt es dann nicht;
 * mit Kapiteln steht das laufende über dem Titel, und ein Knopf neben dem Sleep-Timer öffnet die Liste.
 */
@Composable
fun NowPlayingScreen(
    onOpenSleepTimer: () -> Unit = {},
    onOpenChapters: () -> Unit = {},
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val chapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    NowPlayingHost(
        viewModel,
        onOpenSleepTimer = onOpenSleepTimer,
        chapterTitle = chapter?.title,
        chaptersAvailable = chapters.isNotEmpty(),
        onOpenChapters = onOpenChapters,
    )
}

/**
 * Wiedergabe-Ansicht des Handys (Wear-Konzept 15): dieselbe Ansicht, aber Bedienung und Anzeige gehören dem Handy.
 * Einen Sleep-Timer gibt es hier nicht, die Krone ändert die Lautstärke des Handys.
 */
@Composable
fun RemoteNowPlayingScreen(viewModel: RemotePlayerViewModel = hiltViewModel()) {
    NowPlayingHost(viewModel, onOpenSleepTimer = {}, sleepTimerAvailable = false)
}

@Composable
private fun NowPlayingHost(
    model: NowPlayingModel,
    onOpenSleepTimer: () -> Unit,
    sleepTimerAvailable: Boolean = true,
    chapterTitle: String? = null,
    chaptersAvailable: Boolean = false,
    onOpenChapters: () -> Unit = {},
) {
    val state by model.state.collectAsStateWithLifecycle()
    // Als Lambda weitergereicht und erst beim Zeichnen gelesen: Der 500-ms-Takt setzt sonst die ganze Ansicht neu zusammen.
    val position = model.positionMs.collectAsStateWithLifecycle()
    val positionMs = remember(position) { { position.value } }
    val cover by model.cover.collectAsStateWithLifecycle()
    val volume by model.volume.collectAsStateWithLifecycle()
    val volumeVisible by model.volumeVisible.collectAsStateWithLifecycle()
    val seedColor by model.seedColor.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by model.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val context = LocalContext.current
    AlbumColorTheme(seedColor) {
        NowPlayingContent(
            state = state,
            positionMs = positionMs,
            cover = cover,
            volume = volume,
            volumeVisible = volumeVisible,
            sleepTimerAvailable = sleepTimerAvailable,
            sleepTimerRemainingMs = sleepTimerRemainingMs,
            // Läuft ein Timer, schaltet ein Tipp ihn aus; sonst öffnet er die Auswahl.
            onSleepTimer = { if (sleepTimerRemainingMs != null) model.cancelSleepTimer() else onOpenSleepTimer() },
            chapterTitle = chapterTitle,
            chaptersAvailable = chaptersAvailable,
            onChapters = onOpenChapters,
            onPrevious = model::previous,
            onPlayPause = model::togglePlayPause,
            onNext = model::next,
            onSkipBack = model::skipBack,
            onSkipForward = model::skipForward,
            onShuffle = model::toggleShuffle,
            onOutput = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
            onCrown = model::onCrown,
            onSeek = model::seekTo,
        )
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    positionMs: () -> Long,
    cover: android.graphics.Bitmap?,
    volume: Float,
    volumeVisible: Boolean,
    sleepTimerAvailable: Boolean,
    sleepTimerRemainingMs: Long?,
    onSleepTimer: () -> Unit,
    chapterTitle: String?,
    chaptersAvailable: Boolean,
    onChapters: () -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    onShuffle: () -> Unit,
    onOutput: () -> Unit,
    onCrown: (Float) -> Unit,
    onSeek: (Long) -> Unit,
) {
    val duration = state.durationMs
    val spoken = state.spoken
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    val currentPositionMs by rememberUpdatedState(positionMs)
    // Nach dem Loslassen bleibt die Anzeige am Ziel, bis der Player nachgekommen ist (die Position wird nur alle 500 ms gelesen),
    // höchstens SEEK_HOLD_MAX_MS lang.
    LaunchedEffect(seekTarget) {
        val target = seekTarget ?: return@LaunchedEffect
        withTimeoutOrNull(SEEK_HOLD_MAX_MS) {
            snapshotFlow { currentPositionMs() }.first { abs(it - target) < SEEK_SETTLE_MS }
        }
        seekTarget = null
    }
    val shownPositionMs: () -> Long = remember(duration) {
        { dragProgress?.let { (it * duration).toLong() } ?: seekTarget ?: currentPositionMs() }
    }
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
            CoverBackground(cover)
            // Vor der Steuerung gezeichnet: Berührungen auf den Knöpfen gehen an die Knöpfe, nicht an den Ring.
            SeekRing(
                positionMs = shownPositionMs,
                durationMs = duration,
                spoken = spoken != null,
                onPreview = { dragProgress = it },
                onSeek = { ms ->
                    seekTarget = ms
                    onSeek(ms)
                },
                modifier = Modifier.fillMaxSize(),
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
                    if (spoken != null && chapterTitle != null) {
                        Text(
                            chapterTitle,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Text(
                        current?.title ?: spoken?.title ?: stringResource(R.string.player_nothing),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        if (state.outputSuppressed) stringResource(R.string.player_no_output) else current?.artist ?: spoken?.author.orEmpty(),
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
                    if (spoken != null) {
                        val back = spoken.seek.backSeconds
                        SeekButton(R.drawable.ic_replay, back, pluralStringResource(R.plurals.player_skip_back, back, back), onSkipBack)
                    } else {
                        ControlButton(R.drawable.ic_skip_previous, R.string.player_previous, SKIP_BUTTON_SIZE, onPrevious)
                    }
                    ControlButton(
                        if (state.isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                        if (state.isPlaying) R.string.player_pause else R.string.player_play,
                        PLAY_BUTTON_SIZE,
                        onPlayPause,
                        active = true,
                    )
                    if (spoken != null) {
                        val forward = spoken.seek.forwardSeconds
                        SeekButton(R.drawable.ic_forward, forward, pluralStringResource(R.plurals.player_skip_forward, forward, forward), onSkipForward)
                    } else {
                        ControlButton(R.drawable.ic_skip_next, R.string.player_next, SKIP_BUTTON_SIZE, onNext)
                    }
                }
                // Die kleinen Knöpfe mit Abstand zu Wiedergabe/Pause, damit niemand beim Pausieren den Sleep-Timer trifft.
                Column(
                    Modifier.weight(1f).fillMaxWidth().padding(top = SECONDARY_ROW_GAP),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(SECONDARY_SPACING)) {
                        if (spoken == null) {
                            ControlButton(
                                R.drawable.ic_shuffle,
                                R.string.player_shuffle,
                                SECONDARY_BUTTON_SIZE,
                                onShuffle,
                                active = state.shuffle,
                                toggle = true,
                            )
                        }
                        if (sleepTimerAvailable) {
                            ControlButton(
                                R.drawable.ic_bedtime,
                                R.string.sleep_timer_title,
                                SECONDARY_BUTTON_SIZE,
                                onSleepTimer,
                                active = sleepTimerRemainingMs != null,
                            )
                        }
                        if (spoken != null && chaptersAvailable) {
                            ControlButton(R.drawable.ic_list, R.string.player_chapters, SECONDARY_BUTTON_SIZE, onChapters)
                        }
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

/**
 * [ProgressRing] mit Ziehen und Bedienhilfe. Der Ring liest die Position erst beim Zeichnen; neu zusammengesetzt wird nur, wenn
 * sich die angesagte Sekunde ändert.
 */
@Composable
private fun SeekRing(
    positionMs: () -> Long,
    durationMs: Long,
    spoken: Boolean,
    onPreview: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPositionMs by rememberUpdatedState(positionMs)
    val second by remember { derivedStateOf { currentPositionMs() / 1000 } }
    val shownMs = second * 1000
    val positionLabel = stringResource(if (spoken) R.string.player_position_spoken else R.string.player_position)
    ProgressRing(
        progress = { if (durationMs > 0) (currentPositionMs().toFloat() / durationMs).coerceIn(0f, 1f) else 0f },
        modifier = modifier
            .ringSeek(
                enabled = durationMs > 0,
                bandWidth = SEEK_BAND_WIDTH,
                onPreview = onPreview,
                onSeek = { target -> onSeek((target * durationMs).toLong()) },
            )
            .semantics {
                contentDescription = positionLabel
                stateDescription = "${formatPosition(shownMs)} / ${formatPosition(durationMs)}"
                progressBarRangeInfo = ProgressBarRangeInfo(
                    if (durationMs > 0) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                    0f..1f,
                )
                setProgress { target ->
                    onSeek((target.coerceIn(0f, 1f) * durationMs).toLong())
                    true
                }
            },
    )
}

/** Ring um den Rand mit Lücke oben (Wear-Konzept 11); [progress] füllt ihn im Uhrzeigersinn und wird erst beim Zeichnen gelesen. */
@Composable
private fun ProgressRing(progress: () -> Float, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceContainer
    val indicatorColor = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val stroke = RING_STROKE.toPx()
        val topLeft = Offset(stroke / 2, stroke / 2)
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val style = Stroke(width = stroke, cap = StrokeCap.Round)
        drawArc(trackColor, RING_START_ANGLE, RING_SWEEP, false, topLeft, arcSize, style = style)
        val fraction = progress()
        if (fraction > 0f) {
            drawArc(indicatorColor, RING_START_ANGLE, RING_SWEEP * fraction, false, topLeft, arcSize, style = style)
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
private val SEEK_ICON_SIZE = 38.dp
private val CONTROL_SPACING = 2.dp

/** Zufall, Sleep-Timer und Kapitel: kleiner als die Hauptsteuerung und deutlich von ihr abgerückt. */
private val SECONDARY_BUTTON_SIZE = 40.dp
private val SECONDARY_ROW_GAP = 10.dp
private val SECONDARY_SPACING = 8.dp

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
private fun PositionIndicator(positionMs: () -> Long, durationMs: Long, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text("${formatPosition(positionMs())} / ${formatPosition(durationMs)}", style = MaterialTheme.typography.titleMedium)
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

/** Zurück- oder Vorspringen mit der Sekundenzahl im Pfeil (wie im Buch-Player am Handy). */
@Composable
private fun SeekButton(iconRes: Int, seconds: Int, description: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(SKIP_BUTTON_SIZE),
        colors = IconButtonDefaults.filledTonalIconButtonColors(),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(iconRes),
                contentDescription = description,
                // Größer als die anderen Symbole: Die Zahl muss in den Kreis des Pfeils passen.
                modifier = Modifier.size(SEEK_ICON_SIZE),
            )
            // Die Zahl steckt schon in der Beschreibung des Symbols; TalkBack soll sie nicht noch einmal vorlesen.
            Text(seconds.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
        }
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
                positionMs = { 80_000 },
                cover = previewCover(),
                volume = 0.4f,
                volumeVisible = false,
                sleepTimerAvailable = true,
                sleepTimerRemainingMs = 754_000,
                onSleepTimer = {},
                chapterTitle = null,
                chaptersAvailable = false,
                onChapters = {},
                onPrevious = {},
                onPlayPause = {},
                onNext = {},
                onSkipBack = {},
                onSkipForward = {},
                onShuffle = {},
                onOutput = {},
                onCrown = {},
                onSeek = {},
            )
        }
    }
}

@WearPreviewDevices
@Composable
private fun NowPlayingBookPreview() {
    MaterialTheme {
        AlbumColorTheme(seedColor = 0xFF3949AB.toInt()) {
            NowPlayingContent(
                state = PlayerState(
                    spoken = SpokenEntry(1, 0, "Testbuch", "Testautor"),
                    isPlaying = false,
                    durationMs = 3_600_000,
                ),
                positionMs = { 1_200_000 },
                cover = previewCover(),
                volume = 0.4f,
                volumeVisible = false,
                sleepTimerAvailable = true,
                sleepTimerRemainingMs = 754_000,
                onSleepTimer = {},
                chapterTitle = "Kapitel 3: Der Sturm",
                chaptersAvailable = true,
                onChapters = {},
                onPrevious = {},
                onPlayPause = {},
                onNext = {},
                onSkipBack = {},
                onSkipForward = {},
                onShuffle = {},
                onOutput = {},
                onCrown = {},
                onSeek = {},
            )
        }
    }
}
