package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.layout.Layout
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
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.cast.CastButton
import keinabersmart.kleinaberlaut.cast.rememberCastDeviceName
import keinabersmart.kleinaberlaut.core.designsystem.theme.AlbumColorTheme
import keinabersmart.kleinaberlaut.core.data.spoken.BookCovers
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookTimeline
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
import keinabersmart.kleinaberlaut.ui.player.CoverThenCentered
import keinabersmart.kleinaberlaut.ui.player.PlayerTopBar
import keinabersmart.kleinaberlaut.ui.player.ShortHeight
import keinabersmart.kleinaberlaut.ui.player.SleepTimerLine
import keinabersmart.kleinaberlaut.ui.player.SleepTimerSheet
import keinabersmart.kleinaberlaut.ui.player.ToggleIconButton
import keinabersmart.kleinaberlaut.ui.player.playPauseIcon
import keinabersmart.kleinaberlaut.ui.player.playPauseLabel
import keinabersmart.kleinaberlaut.ui.player.rememberPlaybackPosition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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
    bookCovers: BookCovers,
    private val continueListening: ContinueListening,
    private val launcher: BookLauncher,
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

    /** Grundfarbe des Covers für das Farbschema; `null`, solange sie fehlt. */
    val seedColor: StateFlow<Int?> = bookId
        .mapLatest { id -> id?.let { bookCovers.seedColor(it) } }
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

    /** Es gibt eine weitere Folge in der Warteschlange der Podcasts. */
    val hasNextEpisode: StateFlow<Boolean> = combine(continueListening.episodes, bookId) { queue, current ->
        queue.any { it.book.id != current }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Die Folge von vorn; steht sie, geht es auch gleich los. */
    fun restartEpisode() {
        seekToBook(0)
        if (!state.value.isPlaying) playback.togglePlayPause()
    }

    /** Springt zur nächsten Folge der Warteschlange; sie rückt dabei an deren Anfang (`BookLauncher` → `started`). */
    fun skipToNextEpisode() {
        val current = state.value.spoken?.bookId ?: return
        viewModelScope.launch { continueListening.next(current)?.let(launcher::play) }
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
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    AlbumColorTheme(seedColor) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            BookPlayerContent(onClose, viewModel = viewModel)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookPlayerContent(
    onClose: () -> Unit,
    viewModel: BookPlayerViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val timeline by viewModel.timeline.collectAsStateWithLifecycle()
    val sleepRemaining by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val episode by viewModel.isEpisode.collectAsStateWithLifecycle()
    val hasNextEpisode by viewModel.hasNextEpisode.collectAsStateWithLifecycle()
    val bookWithProgress by viewModel.book.collectAsStateWithLifecycle()
    val entry = state.spoken
    val position by rememberPlaybackPosition { viewModel.bookPositionMs() }
    var dragging by remember { mutableStateOf<Float?>(null) }
    // Ziehen am Kapitelbalken: Abstand zum Kapitelanfang.
    var chapterDragging by remember { mutableStateOf<Float?>(null) }
    var showSpeed by remember { mutableStateOf(false) }
    var showSleep by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // Breit: die Kapitelliste ersetzt im rechten Panel die Beschreibung.
    var chaptersInPane by remember { mutableStateOf(false) }

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
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Jeder Knopf bekommt gleich viel Platz und schrumpft bei Bedarf; nur Abspielen behält seine Größe.
            // Folgen: ganz links neu starten, ganz rechts zur nächsten Folge der Warteschlange.
            if (episode) {
                ControlCell {
                    IconButton(onClick = viewModel::restartEpisode) {
                        Icon(painterResource(DesignR.drawable.ic_restart), contentDescription = stringResource(R.string.spoken_restart_episode))
                    }
                }
            }
            // Kapitel-Knöpfe nur bei Büchern mit Kapiteln; Folgen haben dafür die Kapitelliste.
            if (hasChapters && !episode) {
                ControlCell {
                    IconButton(onClick = viewModel::previousChapter) {
                        Icon(painterResource(DesignR.drawable.ic_skip_previous), contentDescription = stringResource(R.string.spoken_previous_chapter))
                    }
                }
            }
            ControlCell { SeekButton(DesignR.drawable.ic_replay, "30", stringResource(R.string.spoken_seek_back_30), viewModel::skipBack) }
            FilledIconButton(onClick = viewModel::togglePlayPause, modifier = Modifier.size(if (shortHeight) 56.dp else 72.dp)) {
                Icon(
                    painterResource(playPauseIcon(state.isPlaying)),
                    contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    modifier = Modifier.size(if (shortHeight) 28.dp else 36.dp),
                )
            }
            ControlCell { SeekButton(DesignR.drawable.ic_forward, "10", stringResource(R.string.spoken_seek_forward_10), viewModel::skipForward) }
            if (hasChapters && !episode) {
                ControlCell {
                    IconButton(onClick = viewModel::nextChapter) {
                        Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.spoken_next_chapter))
                    }
                }
            }
            if (episode) {
                ControlCell {
                    IconButton(onClick = viewModel::skipToNextEpisode, enabled = hasNextEpisode) {
                        Icon(painterResource(DesignR.drawable.ic_last_page), contentDescription = stringResource(R.string.spoken_next_episode))
                    }
                }
            }
        }
    }
    val description = bookWithProgress?.book?.description.orEmpty()
    val sleepActive = sleepRemaining != null
    val castDeviceName = rememberCastDeviceName(state.isRemote)

    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // Wie bei der Musik: breit (Tablet) links Cover und Steuerung, rechts Beschreibung oder Kapitel; Handy quer
        // links das Cover und rechts alles andere; schmal untereinander.
        val short = maxHeight < ShortHeight && maxWidth > maxHeight
        val wide = maxWidth >= TwoColumnMinWidth && !short

        // Geschwindigkeit, Sleep-Timer, Kapitelliste, schmal auch die Beschreibung.
        val actionRow: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CastButton()
                TextButton(
                    onClick = { showSpeed = true },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (speed == 1f) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    ),
                    modifier = Modifier.semantics { contentDescription = speedLabel },
                ) {
                    Text(stringResource(R.string.spoken_speed_value, formatSpeed(speed)))
                }
                ToggleIconButton(
                    icon = DesignR.drawable.ic_bedtime,
                    active = sleepActive,
                    label = stringResource(if (sleepActive) R.string.sleep_timer_off else R.string.sleep_timer_title),
                    onClick = { if (sleepActive) viewModel.cancelSleepTimer() else showSleep = true },
                )
                if (hasChapters) {
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_list,
                        active = wide && chaptersInPane,
                        label = stringResource(R.string.spoken_chapters),
                        onClick = { if (wide) chaptersInPane = !chaptersInPane else showChapters = true },
                    )
                }
                if (wide) {
                    // Die Beschreibung steht schon rechts; der Knopf holt sie nur hinter den Kapiteln hervor.
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_info,
                        active = !chaptersInPane || !hasChapters,
                        label = stringResource(R.string.spoken_description),
                        onClick = { chaptersInPane = false },
                    )
                } else {
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_info,
                        active = false,
                        label = stringResource(R.string.spoken_description),
                        onClick = { showInfo = true },
                    )
                }
            }
        }
        val titleBlock: @Composable () -> Unit = {
            Text(
                entry.title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (entry.author.isNotBlank()) {
                Text(
                    entry.author,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (castDeviceName != null) {
                Text(
                    stringResource(R.string.cast_playing_on, castDeviceName),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(16.dp))
        }
        val titleAndControls: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                titleBlock()
                progress()
                controls()
            }
        }
        val coverShape = MaterialTheme.shapes.extraLarge

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerTopBar(
                title = stringResource(R.string.player_now_playing),
                closeLabel = stringResource(R.string.player_close),
                onClose = onClose,
            )
            if (short) {
                // Links Cover mit den Knöpfen darunter, rechts Titel und Steuerung.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            BookArt(entry.bookId, Modifier.fillMaxHeight(), coverShape)
                        }
                        actionRow()
                        SleepTimerLine(sleepRemaining)
                    }
                    // Ohne Scrollen: Der Titel bekommt den Platz, den Regler und Knöpfe übrig lassen, und schrumpft dafür.
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Box(Modifier.weight(1f, fill = false).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Text(
                                entry.title,
                                style = MaterialTheme.typography.titleLarge,
                                autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = MaterialTheme.typography.titleLarge.fontSize),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (entry.author.isNotBlank()) {
                            Text(
                                entry.author,
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (castDeviceName != null) {
                            Text(
                                stringResource(R.string.cast_playing_on, castDeviceName),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        progress()
                        controls()
                    }
                }
            } else if (wide) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        // Ohne fillMaxWidth, damit das Cover bei wenig Höhe schrumpft statt die Knöpfe zu überdecken.
                        BookArt(entry.bookId, Modifier.weight(1f, fill = false).widthIn(max = 480.dp), coverShape)
                        actionRow()
                        SleepTimerLine(sleepRemaining)
                        Spacer(Modifier.height(8.dp))
                        titleAndControls()
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (chaptersInPane && tl != null && hasChapters) {
                            ChapterList(tl, chapter?.index, onChapter = viewModel::seekToBook, modifier = Modifier.fillMaxSize().padding(vertical = 8.dp))
                        } else {
                            DescriptionPane(description, Modifier.fillMaxSize())
                        }
                    }
                }
            } else {
                // Cover oben mit den Knöpfen darunter; Titel und Steuerung im übrigen Platz mittig.
                Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxWidth(), verticalArrangement = CoverThenCentered) {
                    Column(Modifier.weight(1f, fill = false).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        BookArt(entry.bookId, Modifier.weight(1f, fill = false).widthIn(max = 420.dp).fillMaxWidth(), coverShape)
                        actionRow()
                        SleepTimerLine(sleepRemaining)
                    }
                    titleAndControls()
                }
            }
        }
    }

    if (showInfo) {
        ModalBottomSheet(onDismissRequest = { showInfo = false }) {
            DescriptionPane(description, Modifier.fillMaxWidth().navigationBarsPadding())
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

/**
 * Gespielte Zeit links, Restzeit rechts, optional ein Text genau in der Mitte (Kapitelname, also über dem Abspielknopf).
 * Die Mitte hängt nicht von der Breite der Zeiten ab: Der Text bekommt höchstens so viel Platz, wie neben der breiteren
 * Zeit auf beiden Seiten frei bleibt.
 */
@Composable
private fun TimeRow(elapsed: String, remaining: String, center: String? = null) {
    Layout(
        content = {
            Text(elapsed, style = MaterialTheme.typography.bodySmall)
            Text(remaining, style = MaterialTheme.typography.bodySmall)
            if (center != null) {
                Text(
                    center,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val start = measurables[0].measure(loose)
        val end = measurables[1].measure(loose)
        val gap = 8.dp.roundToPx()
        val side = maxOf(start.width, end.width)
        val middle = measurables.getOrNull(2)?.measure(loose.copy(maxWidth = (constraints.maxWidth - 2 * (side + gap)).coerceAtLeast(0)))
        val height = maxOf(start.height, end.height, middle?.height ?: 0)
        layout(constraints.maxWidth, height) {
            start.placeRelative(0, (height - start.height) / 2)
            end.placeRelative(constraints.maxWidth - end.width, (height - end.height) / 2)
            middle?.placeRelative((constraints.maxWidth - middle.width) / 2, (height - middle.height) / 2)
        }
    }
}

/** Gleich breite Zelle in der Wiedergabe-Leiste; ein größerer Knopf wird auf die Zellbreite begrenzt. */
@Composable
private fun RowScope.ControlCell(content: @Composable () -> Unit) {
    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { content() }
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
        ChapterList(
            timeline = timeline,
            currentIndex = currentIndex,
            onChapter = {
                onChapter(it)
                onDismiss()
            },
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun ChapterList(
    timeline: BookTimeline,
    currentIndex: Int?,
    onChapter: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Die Liste öffnet beim laufenden Kapitel.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = timeline.chapters.indexOfFirst { it.index == currentIndex }.coerceAtLeast(0))
    LazyColumn(modifier, state = listState) {
        itemsIndexed(timeline.chapters, key = { _, chapter -> chapter.index }) { _, chapter ->
            ListItem(
                headlineContent = { Text(chapterTitle(chapter), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                trailingContent = { Text(formatClock(chapter.startMs), style = MaterialTheme.typography.bodySmall) },
                colors = ListItemDefaults.colors(
                    containerColor = if (chapter.index == currentIndex) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                ),
                modifier = Modifier.clickable { onChapter(chapter.startMs) },
            )
        }
    }
}

/** Beschreibung des Buchs bzw. die Shownotes der Folge. */
@Composable
private fun DescriptionPane(description: String, modifier: Modifier = Modifier) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(stringResource(R.string.spoken_description), style = MaterialTheme.typography.titleSmall)
        Text(
            description.ifBlank { stringResource(R.string.spoken_no_description) },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
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
