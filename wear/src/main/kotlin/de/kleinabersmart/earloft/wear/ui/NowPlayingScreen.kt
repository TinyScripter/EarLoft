package de.kleinabersmart.earloft.wear.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.widget.Toast
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
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
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.createBitmap
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
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.core.playback.SpokenEntry
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Now playing (Wear concept 11): the cover, slightly blurred, fills the circle inside the progress ring; on top of it title and
 * controls, play/pause exactly in the center. Accent colors come from the cover, the crown changes the volume.
 * Without a suitable output (headphones) playback pauses; a tap on the hint opens the Bluetooth settings.
 * For a book or episode the buttons next to play/pause skip by the skip intervals, and there is no shuffle then;
 * with chapters the current one is shown above the title, and a button next to the sleep timer opens the list. For books with
 * chapters the ring only shows the current chapter ([RingWindow]).
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
        chapter = chapter,
        chaptersAvailable = chapters.isNotEmpty(),
        onOpenChapters = onOpenChapters,
    )
}

/**
 * The phone's now playing view (Wear concept 15): the same view, but controls and display belong to the phone.
 * The crown changes the phone's volume; sleep timer (the phone's, only if it offers one) and chapters as on the watch
 * itself.
 */
@Composable
fun RemoteNowPlayingScreen(
    onOpenSleepTimer: () -> Unit = {},
    onOpenChapters: () -> Unit = {},
    viewModel: RemotePlayerViewModel = hiltViewModel(),
) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val chapter by viewModel.currentChapter.collectAsStateWithLifecycle()
    val sleepTimerSupported by viewModel.sleepTimerSupported.collectAsStateWithLifecycle()
    NowPlayingHost(
        viewModel,
        onOpenSleepTimer = onOpenSleepTimer,
        sleepTimerAvailable = sleepTimerSupported,
        chapter = chapter,
        chaptersAvailable = chapters.isNotEmpty(),
        onOpenChapters = onOpenChapters,
    )
}

@Composable
private fun NowPlayingHost(
    model: NowPlayingModel,
    onOpenSleepTimer: () -> Unit,
    sleepTimerAvailable: Boolean = true,
    chapter: BookChapter? = null,
    chaptersAvailable: Boolean = false,
    onOpenChapters: () -> Unit = {},
) {
    val modelState by model.state.collectAsStateWithLifecycle()
    // Passed on as a lambda and only read while drawing: otherwise the 500 ms tick recomposes the whole view.
    val position = model.positionMs.collectAsStateWithLifecycle()
    // Before the first chapter or after the last one (the chapters don't cover the whole book) the ring shows the whole book;
    // otherwise it would stand at 0 or 100 % there. Recomposes only when this changes, not per tick.
    val insideChapter by remember(chapter) {
        derivedStateOf { chapter != null && position.value >= chapter.startMs && position.value < chapter.endMs }
    }
    // For books with chapters the ring only applies to the current chapter; position and length of the models are book times.
    val window = ringWindow(modelState.spoken, chapter.takeIf { insideChapter })
    val state = if (window != null) modelState.copy(durationMs = window.durationMs) else modelState
    val positionMs = remember(position, window) {
        if (window != null) { { window.toRing(position.value) } } else { { position.value } }
    }
    val cover by model.cover.collectAsStateWithLifecycle()
    val volume by model.volume.collectAsStateWithLifecycle()
    val volumeVisible by model.volumeVisible.collectAsStateWithLifecycle()
    val seedColor by model.seedColor.collectAsStateWithLifecycle()
    // The badge shows whole minutes: read only inside derivedStateOf and rounded up to the minute, the 1 s tick of the timer
    // recomposes the view once a minute.
    val sleepTimer = model.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by remember(sleepTimer) {
        derivedStateOf { sleepTimer.value?.let { minutesLeft(it) * MS_PER_MINUTE } }
    }
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
            // If a timer is running, a tap turns it off; otherwise it opens the selection.
            onSleepTimer = { if (sleepTimerRemainingMs != null) model.cancelSleepTimer() else onOpenSleepTimer() },
            chapterTitle = chapter?.title,
            chaptersAvailable = chaptersAvailable,
            onChapters = onOpenChapters,
            onPrevious = model::previous,
            onPlayPause = model::togglePlayPause,
            onNext = model::next,
            onSkipBack = model::skipBack,
            onSkipForward = model::skipForward,
            onShuffle = model::toggleShuffle,
            onRepeat = model::cycleRepeat,
            onOutput = { openBluetoothSettings(context) },
            onCrown = model::onCrown,
            onSeek = { ms -> model.seekTo(window?.toBook(ms) ?: ms) },
            chapterRing = window != null,
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
    onRepeat: () -> Unit,
    onOutput: () -> Unit,
    onCrown: (Float) -> Unit,
    onSeek: (Long) -> Unit,
    chapterRing: Boolean = false,
) {
    val duration = state.durationMs
    val spoken = state.spoken
    var dragProgress by remember { mutableStateOf<Float?>(null) }
    var seekTarget by remember { mutableStateOf<Long?>(null) }
    val currentPositionMs by rememberUpdatedState(positionMs)
    // After releasing, the display stays at the target until the player has caught up (the position is only read every 500 ms),
    // for at most SEEK_HOLD_MAX_MS.
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
    // On a screen change the field loses focus again right away (the list before it is torn down); without focus
    // the crown never gets through. Hence request it again after every loss.
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
            // Drawn before the controls: touches on the buttons go to the buttons, not to the ring.
            SeekRing(
                positionMs = shownPositionMs,
                durationMs = duration,
                positionLabel = stringResource(
                    when {
                        chapterRing -> R.string.player_position_chapter
                        spoken != null -> R.string.player_position_spoken
                        else -> R.string.player_position
                    },
                ),
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
                // Equal space above and below (weight 1 each): this way the controls, and with them play/pause, sit exactly in the center of the circle.
                val chapterLine = chapterTitle?.takeIf { spoken != null }
                RoundTextStack(
                    bottomToCenter = PLAY_BUTTON_SIZE / 2,
                    // Children: [chapter,] title, artist or hint. Without output the hint is the most important, otherwise the title.
                    priority = when {
                        chapterLine == null -> if (state.outputSuppressed) listOf(1, 0) else listOf(0, 1)
                        state.outputSuppressed -> listOf(2, 1, 0)
                        else -> listOf(1, 0, 2)
                    },
                    modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = TEXT_PADDING),
                ) {
                    if (chapterLine != null) {
                        Text(
                            chapterLine,
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
                // The small buttons spaced away from play/pause so that nobody hits the sleep timer when pausing.
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
                            // Cycles as on the phone: off → all → track; the description names the state.
                            ControlButton(
                                if (state.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat,
                                when (state.repeatMode) {
                                    Player.REPEAT_MODE_ONE -> R.string.player_repeat_one
                                    Player.REPEAT_MODE_ALL -> R.string.player_repeat_all
                                    else -> R.string.player_repeat_off
                                },
                                SECONDARY_BUTTON_SIZE,
                                onRepeat,
                                active = state.repeatMode != Player.REPEAT_MODE_OFF,
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
 * The lines above the controls, stacked from bottom to top; the lowest ends [bottomToCenter] above the screen center.
 * On round watches each line only gets the width the cover allows at its height. With large font or a small
 * watch (WO-V1, WO-V16), lines that would reach into the clock or become narrower than [MIN_LINE_WIDTH] are dropped: [priority]
 * lists the children by importance, the first always stays. Empty lines (e.g. without artist) take no space.
 */
@Composable
private fun RoundTextStack(
    bottomToCenter: Dp,
    priority: List<Int>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val round = LocalConfiguration.current.isScreenRound
    val screen = LocalWindowInfo.current.containerSize
    Layout(content, modifier) { measurables, constraints ->
        val centerY = screen.height / 2f
        val textRadius = min(screen.width, screen.height) / 2f - COVER_INSET.toPx()
        val toCenter = bottomToCenter.toPx()
        val heights = measurables.map { it.maxIntrinsicHeight(constraints.maxWidth) }
        // Width at distance d from the center: the chord of the cover circle, on square watches the full width.
        fun widthAt(d: Float): Int {
            if (!round) return constraints.maxWidth
            val half = sqrt((textRadius * textRadius - d * d).coerceAtLeast(0f))
            return (2 * half).toInt().coerceAtMost(constraints.maxWidth)
        }
        // The top line is the narrowest; if it fits, all below it fit.
        fun fits(lines: Collection<Int>): Boolean {
            val total = lines.sumOf { heights[it] }
            val topDistance = toCenter + total
            return total <= constraints.maxHeight &&
                centerY - topDistance >= TIME_TEXT_RESERVE.toPx() &&
                widthAt(topDistance) >= MIN_LINE_WIDTH.roundToPx()
        }
        val lines = priority.filter { measurables[it].maxIntrinsicWidth(Constraints.Infinity) > 0 }.ifEmpty { priority.take(1) }
        val shown = mutableSetOf(lines.first())
        for (index in lines.drop(1)) {
            if (fits(shown + index)) shown += index
        }
        val height = if (constraints.hasBoundedHeight) constraints.maxHeight else shown.sumOf { heights[it] }
        var stacked = 0
        val placed = shown.sortedDescending().map { index ->
            stacked += heights[index]
            val width = widthAt(toCenter + stacked).coerceAtLeast(MIN_LINE_WIDTH.roundToPx()).coerceAtMost(constraints.maxWidth)
            measurables[index].measure(Constraints(maxWidth = width)) to height - stacked
        }
        layout(constraints.maxWidth, height) {
            placed.forEach { (placeable, y) -> placeable.placeRelative((constraints.maxWidth - placeable.width) / 2, y) }
        }
    }
}

/**
 * [ProgressRing] with dragging and accessibility. The ring only reads the position while drawing; it only recomposes when
 * the announced second changes.
 */
@Composable
private fun SeekRing(
    positionMs: () -> Long,
    durationMs: Long,
    positionLabel: String,
    onPreview: (Float?) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentPositionMs by rememberUpdatedState(positionMs)
    val second by remember { derivedStateOf { currentPositionMs() / 1000 } }
    val shownMs = second * 1000
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

/** Ring around the edge with a gap at the top (Wear concept 11); [progress] fills it clockwise and is only read while drawing. */
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

/** The cover as background: large, only slightly blurred, darkened and fading softly to black toward the edge, with a gap to the ring. */
@Composable
private fun CoverBackground(cover: android.graphics.Bitmap?, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(COVER_INSET).clip(CircleShape)) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                // Unbounded: otherwise the blur fades to transparency at the edges of the image.
                modifier = Modifier.fillMaxSize().blur(COVER_BLUR, BlurredEdgeTreatment.Unbounded),
            )
            // Darken: title and buttons must stay readable on bright covers too.
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = COVER_DIM)))
            // Soft to black toward the outside: the cover does not end at a hard edge but blends into the black background.
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

/** Distance of the cover from the edge: ring (5 dp) plus some air so that the cover does not touch the progress ring. */
private val COVER_INSET = 14.dp
private val COVER_BLUR = 3.dp
private const val COVER_DIM = 0.3f

/** From this fraction of the radius on, the gradient to black begins (at the edge it is fully black). */
private const val COVER_FADE_START = 0.6f
private val TEXT_PADDING = 12.dp

/** Space for the clock digits at the top; in sp because they grow with the font size. */
private val TIME_TEXT_RESERVE = 20.sp

/**
 * A line above the controls does not get narrower than this, otherwise it is dropped (about half the width of a small watch). It applies
 * to the top line: another line below it should not shorten the title down to a few letters.
 */
private val MIN_LINE_WIDTH = 96.dp
private val PLAY_BUTTON_SIZE = 64.dp
private val SKIP_BUTTON_SIZE = 52.dp
private val SEEK_ICON_SIZE = 38.dp
private val CONTROL_SPACING = 2.dp

/** Shuffle, sleep timer and chapters: smaller than the main controls and clearly set apart from them. */
private val SECONDARY_BUTTON_SIZE = 40.dp
private val SECONDARY_ROW_GAP = 10.dp
private val SECONDARY_SPACING = 8.dp

/** Volume bar in the center while the crown is being turned. */
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

/** Time while dragging the ring: "1:23 / 3:45". */
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

/** [active]: filled in the accent color, otherwise muted on the cover. */
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
        // Otherwise the active state is only recognizable by color; screen readers get it as "selected".
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

/** Skip back or forward with the number of seconds in the arrow (like in the book player on the phone). */
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
                // Larger than the other icons: the number has to fit into the arrow's circle.
                modifier = Modifier.size(SEEK_ICON_SIZE),
            )
            // The number is already in the icon's description; TalkBack should not read it out again.
            Text(seconds.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
        }
    }
}

/** Colorful sample cover for the preview (gradient with a bright spot, as real covers often have). */
private fun previewCover(): android.graphics.Bitmap {
    val size = 200
    val bitmap = createBitmap(size, size)
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
                    current = QueueEntry(1, "Moonlight", "Night Moth", 1),
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
                onRepeat = {},
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
                    spoken = SpokenEntry(1, 0, "Test book", "Test author"),
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
                chapterTitle = "Chapter 3: The Storm",
                chaptersAvailable = true,
                onChapters = {},
                onPrevious = {},
                onPlayPause = {},
                onNext = {},
                onSkipBack = {},
                onSkipForward = {},
                onShuffle = {},
                onRepeat = {},
                onOutput = {},
                onCrown = {},
                onSeek = {},
            )
        }
    }
}

/**
 * The watch's Bluetooth page to connect headphones, closing again once one is connected (Wear extras, as the system's own
 * output switcher does); without such a page a hint instead of a crash (review2 J91).
 */
private fun openBluetoothSettings(context: Context) {
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .putExtra(EXTRA_CONNECTION_ONLY, true)
        .putExtra(EXTRA_CLOSE_ON_CONNECT, true)
        .putExtra(EXTRA_BLUETOOTH_FILTER_TYPE, FILTER_TYPE_AUDIO)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("NowPlayingScreen", "No Bluetooth settings on this watch", e)
        Toast.makeText(context, R.string.output_settings_missing, Toast.LENGTH_LONG).show()
    }
}

// Extras of the Wear OS Bluetooth settings (documented for `ACTION_BLUETOOTH_SETTINGS` on Wear OS, "Audio" in the guidelines).
private const val EXTRA_CONNECTION_ONLY = "EXTRA_CONNECTION_ONLY"
private const val EXTRA_CLOSE_ON_CONNECT = "EXTRA_CLOSE_ON_CONNECT"
private const val EXTRA_BLUETOOTH_FILTER_TYPE = "android.bluetooth.devicepicker.extra.FILTER_TYPE"
private const val FILTER_TYPE_AUDIO = 1
