package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.platform.LocalConfiguration
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
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
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

    /** Podcast-Folgen haben nur den einen Regler über die ganze Länge. */
    val isEpisode: StateFlow<Boolean> = book
        .map { it?.book?.kind == BookKind.EPISODE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Zeitachse des laufenden Buchs; `null`, bis Dateien und Kapitel geladen sind. */
    val timeline: StateFlow<BookTimeline?> = bookId
        .mapLatest { id -> id?.let { books.timeline(it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val sleepTimerRemainingMs: StateFlow<Long?> = playback.sleepTimerRemainingMs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Länge des ganzen Buchs. Eine Folge, deren Feed keine Dauer nannte, hat sie erst, wenn der Player sie kennt; bis
     * dahin gilt dessen Angabe.
     */
    fun durationMs(): Long = timeline.value?.durationMs?.takeIf { it > 0 } ?: state.value.durationMs

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
    val episode by viewModel.isEpisode.collectAsStateWithLifecycle()
    val entry = state.spoken
    val position by rememberPlaybackPosition { viewModel.bookPositionMs() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Ziehen am Kapitelbalken: Abstand zum Kapitelanfang.
    var chapterDragging by remember { mutableStateOf<Float?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }

    if (entry == null) {
        // Das Buch wurde beendet oder entfernt: zurück, statt eine leere Seite zu zeigen.
        Box(modifier.fillMaxSize())
        return
    }
    val tl = timeline
    val duration = tl?.durationMs?.takeIf { it > 0 } ?: state.durationMs
    val base = dragging?.toLong() ?: position
    val chapter = tl?.chapterAt(base)
    val shown = chapterDragging?.let { (chapter?.startMs ?: 0) + it.toLong() } ?: base
    val speed = state.speed
    val remainingTotal = tl?.remainingBookMs(shown, speed) ?: ((duration - shown).coerceAtLeast(0) / speed).toLong()

    val positionLabel = stringResource(R.string.spoken_position)
    val positionState = stringResource(R.string.spoken_position_state, formatClock(shown), formatClock(duration))
    val speedLabel = stringResource(R.string.spoken_speed_description, formatSpeed(speed))

    // Oben das ganze Buch (zum Spulen), darunter nur das Kapitel; jeweils links gespielt, rechts die Restzeit mit „-“.
    val progress: @Composable () -> Unit = {
        Text(
            entry.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Slider(
            value = shown.coerceIn(0, duration.coerceAtLeast(1)).toFloat(),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { viewModel.seekToBook(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .semantics {
                    contentDescription = positionLabel
                    stateDescription = positionState
                },
        )
        // Bei Folgen gibt es nur diesen einen Regler; ein Kapitelname steht dann zwischen den Zeiten.
        TimeRow(
            elapsed = formatClock(shown),
            remaining = "-" + formatClock(remainingTotal),
            center = if (episode && chapter != null) chapterTitle(chapter) else null,
        )
        // Nur bei Büchern mit Kapiteln.
        val chapterRemaining = tl?.remainingChapterMs(shown, speed)
        if (!episode && chapter != null && chapterRemaining != null) {
            val chapterElapsed = (shown - chapter.startMs).coerceIn(0, chapter.durationMs)
            val chapterLabel = stringResource(R.string.spoken_position_chapter)
            val chapterState = stringResource(R.string.spoken_position_state, formatClock(chapterElapsed), formatClock(chapter.durationMs))
            Slider(
                value = chapterElapsed.toFloat(),
                onValueChange = { chapterDragging = it },
                onValueChangeFinished = {
                    // Knapp vor dem Ende bleiben, damit der Sprung nicht schon im nächsten Kapitel landet.
                    chapterDragging?.let { viewModel.seekToBook(chapter.startMs + it.toLong().coerceAtMost(chapter.durationMs - 1)) }
                    chapterDragging = null
                },
                valueRange = 0f..chapter.durationMs.coerceAtLeast(1).toFloat(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .semantics {
                        contentDescription = chapterLabel
                        stateDescription = chapterState
                    },
            )
            TimeRow(
                elapsed = formatClock(chapterElapsed),
                remaining = "-" + formatClock(chapterRemaining),
                center = chapterTitle(chapter),
            )
        }
    }
    val hasChapters = tl?.hasChapters == true
    // Handy quer (wenig Höhe): kleinerer Abspielknopf, damit die Leiste darunter ohne Scrollen sichtbar bleibt.
    val shortHeight = LocalConfiguration.current.screenHeightDp < 480
    val controls: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(vertical = if (shortHeight) 4.dp else 12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kapitel-Knöpfe nur, wenn das Buch bzw. die Folge welche hat.
            if (hasChapters) {
                IconButton(onClick = viewModel::previousChapter) {
                    Icon(painterResource(DesignR.drawable.ic_skip_previous), contentDescription = stringResource(R.string.spoken_previous_chapter))
                }
            }
            SeekButton(DesignR.drawable.ic_replay, "30", stringResource(R.string.spoken_seek_back_30), viewModel::skipBack)
            FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(if (shortHeight) 56.dp else 72.dp)) {
                Icon(
                    painterResource(playPauseIcon(state.isPlaying)),
                    contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    modifier = Modifier.size(if (shortHeight) 28.dp else 36.dp),
                )
            }
            SeekButton(DesignR.drawable.ic_forward, "10", stringResource(R.string.spoken_seek_forward_10), viewModel::skipForward)
            if (hasChapters) {
                IconButton(onClick = viewModel::nextChapter) {
                    Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.spoken_next_chapter))
                }
            }
        }
    }
    // Geschwindigkeit, Sleep-Timer, Kapitelliste.
    val extras: @Composable () -> Unit = {
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { showSpeed = true }, modifier = Modifier.semantics { contentDescription = speedLabel }) {
                Text(stringResource(R.string.spoken_speed_value, formatSpeed(speed)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { if (sleepRemaining != null) viewModel.cancelSleepTimer() else showSleep = true }) {
                    Icon(painterResource(DesignR.drawable.ic_bedtime), contentDescription = stringResource(R.string.sleep_timer_title))
                }
                sleepRemaining?.let { Text(formatClock(it), style = MaterialTheme.typography.bodySmall) }
            }
            if (hasChapters) {
                IconButton(onClick = { showChapters = true }) {
                    Icon(painterResource(DesignR.drawable.ic_list), contentDescription = stringResource(R.string.spoken_chapters))
                }
            }
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // Breit (Tablet, Handy quer): links das Cover, rechts alles andere; schmal untereinander.
        val wide = maxWidth >= TwoColumnMinWidth
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = stringResource(R.string.spoken_close))
                }
            }
            if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Box(Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp), contentAlignment = Alignment.Center) {
                        // Ohne fillMaxWidth, damit das Cover bei wenig Höhe schrumpft.
                        BookArt(entry.bookId, Modifier.widthIn(max = 480.dp))
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        progress()
                        controls()
                        extras()
                    }
                }
            } else {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    BookArt(entry.bookId, Modifier.widthIn(max = 320.dp).fillMaxWidth())
                }
                progress()
                controls()
                extras()
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

/** Gespielte Zeit links, Restzeit rechts, optional ein Text in der Mitte (Kapitelname). */
@Composable
private fun TimeRow(elapsed: String, remaining: String, center: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(elapsed, style = MaterialTheme.typography.bodySmall)
        if (center != null) {
            Text(
                center,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        Text(remaining, style = MaterialTheme.typography.bodySmall)
    }
}

/** Zurück- oder Vorspringen mit der Sekundenzahl im Pfeil. */
@Composable
private fun SeekButton(icon: Int, seconds: String, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(56.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(icon), contentDescription = description, modifier = Modifier.size(40.dp))
            // Die Zahl steckt schon in der Beschreibung des Symbols; TalkBack soll sie nicht noch einmal vorlesen.
            Text(seconds, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics {})
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
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1f, 1.25f, 1.5f, 2f).forEach { preset ->
                        FilterChip(
                            selected = snapSpeed(speed) == preset,
                            onClick = { onSpeed(preset) },
                            label = { Text(stringResource(R.string.spoken_speed_value, formatSpeed(preset))) },
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
    val duration = durationMs()
    return if (duration > 0) (bookPositionMs().toFloat() / duration).coerceIn(0f, 1f) else 0f
}
