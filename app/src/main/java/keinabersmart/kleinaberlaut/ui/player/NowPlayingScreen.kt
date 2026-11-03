package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.cast.CastButton
import keinabersmart.kleinaberlaut.cast.rememberCastDeviceName
import keinabersmart.kleinaberlaut.core.designsystem.theme.AlbumColorTheme
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.Lyrics
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.FitSquare
import keinabersmart.kleinaberlaut.ui.components.TwoColumnMinWidth
import keinabersmart.kleinaberlaut.ui.components.formatDuration
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()
    // Erst in den Blättern (Slider, Zeit, Songtext) gelesen, damit nicht der ganze Screen alle 250 ms neu zusammengesetzt wird.
    val position = rememberPlaybackPosition(state.isPlaying, viewModel.positionJumps, state.current) { viewModel.currentPositionMs }
    val lyricsVisible by viewModel.lyricsVisible.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    AlbumColorTheme(seedColor) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NowPlayingContent(
                state = state,
                isFavorite = isFavorite,
                positionMs = { position.value },
                lyricsVisible = lyricsVisible,
                lyrics = lyrics,
                onToggleLyrics = viewModel::toggleLyrics,
                onRetryLyrics = viewModel::retryLyrics,
                onClose = onClose,
                onOpenQueue = onOpenQueue,
                onTogglePlayPause = viewModel::togglePlayPause,
                onNext = viewModel::next,
                onPrevious = viewModel::previous,
                onSeek = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onToggleFavorite = viewModel::toggleFavorite,
                sleepTimerRemainingMs = sleepTimerRemainingMs,
                onStartSleepTimer = viewModel::startSleepTimer,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                castDeviceName = rememberCastDeviceName(state.isRemote),
                castButton = { CastButton(active = state.isRemote) },
                queuePane = { paneModifier ->
                    QueueList(
                        state = state,
                        onItemClick = viewModel::skipToQueueItem,
                        onRemove = viewModel::removeQueueItem,
                        onMove = viewModel::moveQueueItem,
                        modifier = paneModifier,
                    )
                },
            )
        }
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    isFavorite: Boolean,
    positionMs: () -> Long,
    lyricsVisible: Boolean,
    lyrics: Lyrics?,
    onToggleLyrics: () -> Unit,
    onRetryLyrics: () -> Unit,
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleFavorite: () -> Unit,
    sleepTimerRemainingMs: Long?,
    onStartSleepTimer: (minutes: Int) -> Unit,
    onCancelSleepTimer: () -> Unit,
    modifier: Modifier = Modifier,
    castDeviceName: String? = null,
    castButton: @Composable () -> Unit = {},
    /** Warteschlange rechts neben der Wiedergabe auf breiten Fenstern; schmal öffnet [onOpenQueue] eine eigene Seite. */
    queuePane: @Composable (Modifier) -> Unit = {},
) {
    var sleepSheetVisible by remember { mutableStateOf(false) }
    if (sleepSheetVisible) {
        SleepTimerSheet(
            onStart = onStartSleepTimer,
            onDismiss = { sleepSheetVisible = false },
        )
    }
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // Breit (Tablet quer): links Cover und Steuerung, rechts Warteschlange oder Songtext.
        // Handy quer: zu niedrig für Cover über Titel und Steuerung, daher links Cover mit den Knöpfen, rechts der Rest.
        val short = maxHeight < ShortHeight && maxWidth > maxHeight
        val wide = maxWidth >= TwoColumnMinWidth && !short
        // Auf kleinen Handys bekommt das Hochformat unten etwas Rand, sonst klebt der Play-Knopf am Bildschirmrand.
        val tight = maxHeight < 700.dp

        // Läuft ein Timer, schaltet ein Tipp auf den Mond ihn aus; sonst öffnet er die Auswahl.
        val sleepActive = sleepTimerRemainingMs != null
        val actionRow: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                castButton()
                if (state.current != null) {
                    ToggleIconButton(
                        icon = if (isFavorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border,
                        active = isFavorite,
                        label = stringResource(if (isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite),
                        onClick = onToggleFavorite,
                    )
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_lyrics,
                        active = lyricsVisible,
                        label = stringResource(if (lyricsVisible) R.string.player_hide_lyrics else R.string.player_show_lyrics),
                        onClick = onToggleLyrics,
                    )
                }
                if (wide) {
                    // Die Warteschlange steht schon rechts; der Knopf holt sie nur hinter dem Songtext hervor.
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_queue_music,
                        active = !lyricsVisible,
                        label = stringResource(R.string.player_open_queue),
                        onClick = { if (lyricsVisible) onToggleLyrics() },
                    )
                } else {
                    IconButton(onClick = onOpenQueue) {
                        Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = stringResource(R.string.player_open_queue))
                    }
                }
                if (state.current != null) {
                    ToggleIconButton(
                        icon = DesignR.drawable.ic_bedtime,
                        active = sleepActive,
                        label = stringResource(if (sleepActive) R.string.sleep_timer_off else R.string.sleep_timer_title),
                        onClick = { if (sleepActive) onCancelSleepTimer() else sleepSheetVisible = true },
                    )
                }
            }
        }
        val trackInfoAndControls: @Composable (QueueEntry) -> Unit = { current ->
            TrackInfoAndControls(
                current = current,
                state = state,
                positionMs = positionMs,
                castDeviceName = castDeviceName,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
            )
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PlayerTopBar(stringResource(R.string.player_now_playing), stringResource(R.string.player_close), onClose)
            val current = state.current
            if (current == null) {
                actionRow()
                EmptyHint(stringResource(R.string.player_nothing_playing))
            } else if (short) {
                // Links Cover (oder Songtext) mit den Knöpfen darunter, rechts Titel und Steuerung.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyricsVisible) {
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                LyricsPane(lyrics = lyrics, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
                            }
                        } else {
                            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                AlbumArt(
                                    albumId = current.albumId,
                                    modifier = Modifier.fillMaxHeight(),
                                    shape = MaterialTheme.shapes.extraLarge,
                                )
                            }
                        }
                        actionRow()
                        SleepTimerLine(sleepTimerRemainingMs)
                    }
                    Column(
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        trackInfoAndControls(current)
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
                        AlbumArt(
                            albumId = current.albumId,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(max = 480.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                        actionRow()
                        SleepTimerLine(sleepTimerRemainingMs)
                        Spacer(Modifier.height(8.dp))
                        trackInfoAndControls(current)
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (lyricsVisible) {
                            LyricsPane(lyrics = lyrics, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
                        } else {
                            queuePane(Modifier.fillMaxSize().padding(vertical = 8.dp))
                        }
                    }
                }
            } else {
                // Cover oben mit den Knöpfen darunter; alles ab dem Titel sitzt im übrigen Platz mittig (oben und unten
                // gleich viel Luft). Mit Songtext bekommt dieser den ganzen Platz, und der Titelblock rückt nach unten.
                // Auf Tablets im Hochformat nicht über die ganze Breite ziehen.
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = 560.dp)
                        .fillMaxWidth()
                        .padding(bottom = if (tight) 16.dp else 0.dp),
                    verticalArrangement = if (lyricsVisible) Arrangement.Top else CoverThenCentered,
                ) {
                    Column(Modifier.weight(1f, fill = lyricsVisible).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyricsVisible) {
                            Box(Modifier.weight(1f).fillMaxWidth()) {
                                LyricsPane(lyrics = lyrics, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
                            }
                        } else {
                            // Quadrat im Restplatz, mit etwas Luft zur Kopfzeile und zur Knopfreihe.
                            FitSquare(
                                Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                maxSide = 420.dp,
                            ) { fit ->
                                AlbumArt(albumId = current.albumId, modifier = fit, shape = MaterialTheme.shapes.extraLarge)
                            }
                        }
                        actionRow()
                        SleepTimerLine(sleepTimerRemainingMs)
                    }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyricsVisible) Spacer(Modifier.height(24.dp))
                        trackInfoAndControls(current)
                        if (lyricsVisible) Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

/** Darunter (und breiter als hoch) liegt Now Playing in zwei Spalten statt Cover über den Knöpfen. */
internal val ShortHeight = 480.dp

/** Kopfzeile aller Wiedergabe-Ansichten (Musik, Bücher, Folgen): Schließen links, Titel mittig. */
@Composable
internal fun PlayerTopBar(title: String, closeLabel: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onClose) {
            Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = closeLabel)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // So groß wie der Schließen-Knopf: Der Titel bleibt mittig.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
internal fun SleepTimerLine(remainingMs: Long?) {
    if (remainingMs == null) return
    // Eigene Zeile ohne Kürzung, damit die Restzeit auch bei langen Zeiten und großer Schrift ganz zu lesen ist.
    Text(
        stringResource(R.string.sleep_timer_remaining, formatDuration(remainingMs)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Titel, Interpret, Album, Cast-Hinweis, Fortschritt und die Wiedergabe-Knöpfe. */
@Composable
private fun TrackInfoAndControls(
    current: QueueEntry,
    state: PlayerState,
    positionMs: () -> Long,
    castDeviceName: String?,
    onSeek: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onPrevious: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    onCycleRepeat: () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            current.title,
            style = MaterialTheme.typography.headlineSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        Text(
            current.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (current.albumTitle.isNotEmpty()) {
            Text(
                current.albumTitle,
                style = MaterialTheme.typography.bodyMedium,
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
        SeekBar(durationMs = state.durationMs, positionMs = positionMs, onSeek = onSeek)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToggleIconButton(
                icon = DesignR.drawable.ic_shuffle,
                active = state.shuffle,
                label = stringResource(if (state.shuffle) R.string.player_shuffle_on else R.string.player_shuffle_off),
                onClick = onToggleShuffle,
            )
            IconButton(onClick = onPrevious, modifier = Modifier.size(56.dp)) {
                Icon(
                    painterResource(DesignR.drawable.ic_skip_previous),
                    contentDescription = stringResource(R.string.action_previous),
                    modifier = Modifier.size(32.dp),
                )
            }
            FilledIconButton(onClick = onTogglePlayPause, modifier = Modifier.size(72.dp)) {
                Icon(
                    painterResource(playPauseIcon(state.isPlaying)),
                    contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(onClick = onNext, modifier = Modifier.size(56.dp)) {
                Icon(
                    painterResource(DesignR.drawable.ic_skip_next),
                    contentDescription = stringResource(R.string.action_next),
                    modifier = Modifier.size(32.dp),
                )
            }
            val (repeatIcon, repeatLabel) = when (state.repeatMode) {
                Player.REPEAT_MODE_ONE -> DesignR.drawable.ic_repeat_one to R.string.player_repeat_one
                Player.REPEAT_MODE_ALL -> DesignR.drawable.ic_repeat to R.string.player_repeat_all
                else -> DesignR.drawable.ic_repeat to R.string.player_repeat_off
            }
            ToggleIconButton(
                icon = repeatIcon,
                active = state.repeatMode != Player.REPEAT_MODE_OFF,
                label = stringResource(repeatLabel),
                onClick = onCycleRepeat,
            )
        }
    }
}

/**
 * Zwei Kinder: Cover samt Knöpfen ganz oben, der Titelblock danach mit dem restlichen Platz je zur Hälfte davor und
 * dahinter, damit der freie Raum oben und unten gleich groß ist.
 */
internal object CoverThenCentered : Arrangement.Vertical {
    override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
        val free = (totalSize - sizes.sum()).coerceAtLeast(0)
        var position = 0
        sizes.forEachIndexed { index, size ->
            if (index == 1) position += free / 2
            outPositions[index] = position
            position += size
        }
    }
}

@Composable
private fun SeekBar(durationMs: Long, positionMs: () -> Long, onSeek: (Long) -> Unit) {
    // Während des Ziehens die Fingerposition zeigen, nicht die (verzögerte) Player-Position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val position = positionMs()
    val fraction = dragFraction ?: if (durationMs > 0) (position.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val seekLabel = stringResource(R.string.player_seek)

    Column(Modifier.fillMaxWidth()) {
        Slider(
            value = fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * durationMs).toLong()) }
                dragFraction = null
            },
            enabled = durationMs > 0,
            modifier = Modifier.semantics { contentDescription = seekLabel },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: position
            Text(formatDuration(shownMs), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
internal fun ToggleIconButton(icon: Int, active: Boolean, label: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) {
        Icon(painterResource(icon), contentDescription = label)
    }
}

@Preview(showBackground = true, heightDp = 760)
@Preview(name = "Tablet", showBackground = true, device = Devices.TABLET)
@Composable
private fun NowPlayingContentPreview() {
    KleinaberlautTheme {
        NowPlayingContent(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "Ein Lied", artist = "Eine Band", albumId = null, albumTitle = "Ein Album"),
                isPlaying = true,
                durationMs = 200_000,
                shuffle = true,
                repeatMode = Player.REPEAT_MODE_ALL,
            ),
            isFavorite = true,
            positionMs = { 80_000 },
            lyricsVisible = false,
            lyrics = null,
            onToggleLyrics = {},
            onRetryLyrics = {},
            onClose = {},
            onOpenQueue = {},
            onTogglePlayPause = {},
            onNext = {},
            onPrevious = {},
            onSeek = {},
            onToggleShuffle = {},
            onCycleRepeat = {},
            onToggleFavorite = {},
            sleepTimerRemainingMs = 754_000,
            onStartSleepTimer = {},
            onCancelSleepTimer = {},
        )
    }
}
