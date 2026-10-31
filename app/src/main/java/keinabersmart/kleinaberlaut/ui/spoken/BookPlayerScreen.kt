package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.ui.player.SleepTimerSheet
import keinabersmart.kleinaberlaut.ui.player.playPauseIcon
import keinabersmart.kleinaberlaut.ui.player.playPauseLabel
import keinabersmart.kleinaberlaut.ui.player.rememberPlaybackPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class BookPlayerViewModel @javax.inject.Inject constructor(
    private val playback: PlaybackConnection,
    books: BookRepository,
) : ViewModel() {

    val state: StateFlow<PlayerState> = playback.state

    private val bookId = state.map { it.spoken?.bookId }.distinctUntilChanged()

    val book: StateFlow<BookWithProgress?> = bookId
        .flatMapLatest { id -> if (id == null) flowOf(null) else books.book(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Zeitachse des laufenden Buchs; `null`, bis Dateien und Kapitel geladen sind. */
    val timeline: StateFlow<BookTimeline?> = bookId
        .mapLatest { id -> id?.let { books.timeline(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Aktuelle Position im ganzen Buch. */
    fun bookPositionMs(): Long {
        val timeline = timeline.value ?: return 0
        val entry = state.value.spoken ?: return 0
        return timeline.toBookPosition(entry.trackIndex, playback.currentPositionMs)
    }

    fun seekToBook(positionMs: Long) {
        val target = timeline.value?.locate(positionMs) ?: return
        playback.seekToTrack(target.trackIndex, target.offsetMs)
    }

    fun previousChapter() {
        val timeline = timeline.value ?: return
        timeline.previousChapterStart(bookPositionMs())?.let(::seekToBook)
    }

    fun nextChapter() {
        val timeline = timeline.value ?: return
        timeline.nextChapter(bookPositionMs())?.let { seekToBook(it.startMs) }
    }

    fun togglePlayPause() = playback.togglePlayPause()
    fun skipBack() = playback.skipBack()
    fun skipForward() = playback.skipForward()
    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun startSleepTimer(minutes: Int) = playback.startSleepTimer(minutes)
    fun cancelSleepTimer() = playback.cancelSleepTimer()
}

/** Wiedergabe-Ansicht für Hörbücher: Kapitel, Restzeiten, −30 s / +10 s, Geschwindigkeit (Konzept 12.5). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookPlayerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookPlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val entry = state.spoken
    val position by rememberPlaybackPosition { viewModel.bookPositionMs() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    if (entry == null) {
        // Das Buch wurde beendet oder entfernt: zurück, statt eine leere Seite zu zeigen.
        Box(modifier.fillMaxSize())
        return
    }
    val tl = timeline
    val duration = tl?.durationMs ?: state.durationMs
    val shown = dragging?.toLong() ?: position
    val chapter = tl?.chapterAt(shown)
    val speed = state.speed
    val remainingTotal = tl?.remainingBookMs(shown, speed) ?: ((duration - shown).coerceAtLeast(0) / speed).toLong()

    Column(
        modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = stringResource(R.string.spoken_close))
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            BookArt(entry.bookId, Modifier.widthIn(max = 320.dp).fillMaxWidth())
        }
        Text(
            entry.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(entry.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        // Nur mit Kapiteln: Name des aktuellen Kapitels.
        if (chapter != null) {
            Text(
                chapterTitle(chapter),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        Slider(
            value = shown.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { viewModel.seekToBook(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatClock(shown), style = MaterialTheme.typography.bodySmall)
            Text(formatClock(duration), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            stringResource(R.string.spoken_remaining_total, formatDuration(remainingTotal)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp),
        )
        // Die Restzeit im Kapitel gibt es nur bei Büchern mit Kapiteln.
        tl?.remainingChapterMs(shown, speed)?.let { remaining ->
            Text(
                stringResource(R.string.spoken_remaining_chapter, formatDuration(remaining)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::previousChapter, enabled = tl?.hasChapters == true) {
                Icon(painterResource(DesignR.drawable.ic_skip_previous), contentDescription = stringResource(R.string.spoken_previous_chapter))
            }
            SeekButton(DesignR.drawable.ic_replay, "30", stringResource(R.string.spoken_seek_back_30), viewModel::skipBack)
            FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    painterResource(playPauseIcon(state.isPlaying)),
                    contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    modifier = Modifier.size(36.dp),
                )
            }
            SeekButton(DesignR.drawable.ic_forward, "10", stringResource(R.string.spoken_seek_forward_10), viewModel::skipForward)
            IconButton(onClick = viewModel::nextChapter, enabled = tl?.hasChapters == true) {
                Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.spoken_next_chapter))
            }
        }

        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showSpeed = true }) {
                Text(stringResource(R.string.spoken_speed_value, formatSpeed(speed)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (sleepRemaining != null) viewModel.cancelSleepTimer() else showSleep = true }) {
                    Icon(painterResource(DesignR.drawable.ic_bedtime), contentDescription = stringResource(R.string.sleep_timer_title))
                }
                sleepRemaining?.let { Text(formatClock(it), style = MaterialTheme.typography.bodySmall) }
            }
            IconButton(onClick = { showChapters = true }, enabled = tl?.hasChapters == true) {
                Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = stringResource(R.string.spoken_chapters))
            }
        }
    }

    if (showSpeed) SpeedDialog(speed, onSpeed = viewModel::setSpeed, onDismiss = { showSpeed = false })
    if (showSleep) SleepTimerSheet(onStart = viewModel::startSleepTimer, onDismiss = { showSleep = false })
    if (showChapters && tl != null) {
        ChapterSheet(
            timeline = tl,
            currentIndex = chapter?.index,
            onChapter = { viewModel.seekToBook(it) },
            onDismiss = { showChapters = false },
        )
    }
}

/** Zurück- oder Vorspringen mit der Sekundenzahl im Pfeil. */
@Composable
private fun SeekButton(icon: Int, seconds: String, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(40.dp))
            Text(seconds, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun SpeedDialog(speed: Float, onSpeed: (Float) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.spoken_speed)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.spoken_speed_value, formatSpeed(speed)),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Slider(
                    value = speed,
                    onValueChange = { onSpeed(snapSpeed(it)) },
                    valueRange = MIN_SPEED..MAX_SPEED,
                    steps = ((MAX_SPEED - MIN_SPEED) / SPEED_STEP).toInt() - 1,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        FilterChip(
                            selected = snapSpeed(speed) == preset,
                            onClick = { onSpeed(preset) },
                            label = { Text(formatSpeed(preset)) },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.spoken_close)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheet(
    timeline: BookTimeline,
    currentIndex: Int?,
    onChapter: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(Modifier.navigationBarsPadding()) {
            itemsIndexed(timeline.chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
                ListItem(
                    headlineContent = { Text(chapterTitle(chapter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    trailingContent = { Text(formatClock(chapter.startMs), style = MaterialTheme.typography.bodySmall) },
                    colors = ListItemDefaults.colors(
                        containerColor = if (chapter.index == currentIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    ),
                    modifier = Modifier.clickable {
                        onChapter(chapter.startMs)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** Mini-Player für Hörbücher: Cover, Titel, −30 s, Wiedergabe/Pause, +10 s, dünner Fortschrittsbalken des Buchs. */
@Composable
fun SpokenMiniPlayer(
    state: PlayerState,
    fractionProvider: () -> Float,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val entry = state.spoken ?: return
    val fraction by rememberPlaybackPosition { (fractionProvider() * 1000).toLong() }
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { (fraction / 1000f).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                BookArt(entry.bookId, Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        entry.author,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SeekButton(DesignR.drawable.ic_replay, "30", stringResource(R.string.spoken_seek_back_30), onSkipBack)
                IconButton(onClick = onTogglePlayPause) {
                    Icon(painterResource(playPauseIcon(state.isPlaying)), contentDescription = stringResource(playPauseLabel(state.isPlaying)))
                }
                SeekButton(DesignR.drawable.ic_forward, "10", stringResource(R.string.spoken_seek_forward_10), onSkipForward)
            }
        }
    }
}

private const val MIN_SPEED = 0.5f
private const val MAX_SPEED = 3f
private const val SPEED_STEP = 0.05f

/** Rundet auf Schritte von 0,05, damit Regler und Anzeige zusammenpassen. */
private fun snapSpeed(value: Float): Float = (Math.round(value / SPEED_STEP) * SPEED_STEP).coerceIn(MIN_SPEED, MAX_SPEED)

/** „1“, „1,25“, „1,5“ – ohne überflüssige Nullen. */
private fun formatSpeed(speed: Float): String =
    "%.2f".format(snapSpeed(speed)).trimEnd('0').trimEnd(',', '.')

/** Der Anteil des Buchs für den Mini-Player. */
fun BookPlayerViewModel.bookFraction(): Float {
    val duration = timeline.value?.durationMs ?: return 0f
    return if (duration > 0) (bookPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
}
