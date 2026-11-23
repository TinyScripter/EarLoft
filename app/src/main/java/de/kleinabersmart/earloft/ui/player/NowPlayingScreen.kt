package de.kleinabersmart.earloft.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import de.kleinabersmart.earloft.ui.playlists.AddToPlaylistDialog
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.cast.CastButton
import de.kleinabersmart.earloft.cast.rememberCastDeviceName
import de.kleinabersmart.earloft.ui.theme.PlayerColorTheme
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.data.repository.SongLyrics
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.ArtistPicker
import de.kleinabersmart.earloft.ui.components.linkClick
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.FitSquare
import de.kleinabersmart.earloft.ui.components.MixMenuItem
import de.kleinabersmart.earloft.core.data.mix.MixSeed
import de.kleinabersmart.earloft.ui.components.ShareButton
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.formatDuration
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenArtist: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val isFavorite by viewModel.isCurrentFavorite.collectAsStateWithLifecycle()
    // Read only in the leaves (slider, time, lyrics) so the whole screen isn't recomposed every 250 ms.
    val position = rememberPlaybackPosition(state.isPlaying, viewModel.positionJumps, state.current) { viewModel.currentPositionMs }
    val lyricsVisible by viewModel.lyricsVisible.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    val sleepTimerRemainingMs by viewModel.sleepTimerRemainingMs.collectAsStateWithLifecycle()
    val sleepTimerToEnd by viewModel.sleepTimerToEnd.collectAsStateWithLifecycle()
    PlayerColorTheme(seedColor) {
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
                sleepTimerToEnd = sleepTimerToEnd,
                onStartSleepTimer = viewModel::startSleepTimer,
                onStartSleepTimerToEnd = viewModel::startSleepTimerToEnd,
                onCancelSleepTimer = viewModel::cancelSleepTimer,
                castDeviceName = rememberCastDeviceName(state.isRemote),
                castButton = { CastButton(active = state.isRemote) },
                links = PlayerLinks(onOpenAlbum = onOpenAlbum, onOpenArtist = onOpenArtist, artistsOf = viewModel::artistsOf),
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
    lyrics: SongLyrics?,
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
    sleepTimerToEnd: Boolean = false,
    onStartSleepTimerToEnd: () -> Unit = {},
    castDeviceName: String? = null,
    castButton: @Composable () -> Unit = {},
    links: PlayerLinks = PlayerLinks.None,
    /** Queue to the right of playback on wide windows; when narrow, [onOpenQueue] opens a separate page. */
    queuePane: @Composable (Modifier) -> Unit = {},
) {
    var sleepSheetVisible by remember { mutableStateOf(false) }
    if (sleepSheetVisible) {
        // "End of song" first, as books offer "End of chapter".
        SleepTimerSheet(
            onStart = onStartSleepTimer,
            onDismiss = { sleepSheetVisible = false },
            toEnd = SleepTimerEnd.SONG,
            onStartToEnd = onStartSleepTimerToEnd,
        )
    }
    BoxWithConstraints(modifier.fillMaxSize().safeDrawingPadding()) {
        // Wide (tablet in landscape): cover and controls on the left, queue or lyrics on the right.
        // Phone in landscape: too low for cover above title and controls, hence cover with the buttons on the left, the rest on the right.
        val short = maxHeight < ShortHeight && maxWidth > maxHeight
        val wide = maxWidth >= TwoColumnMinWidth && !short
        // On small phones portrait gets a bit of bottom margin, otherwise the play button sticks to the screen edge.
        val tight = maxHeight < 700.dp

        // If a timer is running, tapping the moon turns it off; otherwise it opens the selection.
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
                    // The queue is already on the right; the button only brings it out from behind the lyrics.
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
                links = links,
                onSeek = onSeek,
                onToggleShuffle = onToggleShuffle,
                onPrevious = onPrevious,
                onTogglePlayPause = onTogglePlayPause,
                onNext = onNext,
                onCycleRepeat = onCycleRepeat,
            )
        }

        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // Share and ⋮ at the top right, as in the book and episode player.
            PlayerTopBar(stringResource(R.string.player_close), onClose) {
                state.current?.let { ShareButton(ShareTarget.Song(it.songId)) }
                PlayerMoreMenu(state, links)
            }
            val current = state.current
            if (current == null) {
                actionRow()
                EmptyHint(stringResource(R.string.player_nothing_playing))
            } else if (short) {
                // Cover (or lyrics) on the left with the buttons below, title and controls on the right.
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).fillMaxHeight().padding(bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (lyricsVisible) {
                            Surface(
                                modifier = Modifier.weight(1f).fillMaxWidth(),
                                shape = MaterialTheme.shapes.extraLarge,
                                color = MaterialTheme.colorScheme.surfaceContainerLow,
                            ) {
                                LyricsPane(lyrics = lyrics?.lyrics, fromLrclib = lyrics?.fromLrclib == true, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
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
                        SleepTimerLine(sleepTimerRemainingMs, toEnd = SleepTimerEnd.SONG.takeIf { sleepTimerToEnd })
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
                        // Without fillMaxWidth so the cover shrinks when there's little height instead of covering the buttons.
                        AlbumArt(
                            albumId = current.albumId,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .widthIn(max = 480.dp),
                            shape = MaterialTheme.shapes.extraLarge,
                        )
                        actionRow()
                        SleepTimerLine(sleepTimerRemainingMs, toEnd = SleepTimerEnd.SONG.takeIf { sleepTimerToEnd })
                        Spacer(Modifier.height(8.dp))
                        trackInfoAndControls(current)
                    }
                    Surface(
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(bottom = 16.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                    ) {
                        if (lyricsVisible) {
                            LyricsPane(lyrics = lyrics?.lyrics, fromLrclib = lyrics?.fromLrclib == true, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
                        } else {
                            queuePane(Modifier.fillMaxSize().padding(vertical = 8.dp))
                        }
                    }
                }
            } else {
                // Cover at the top with the buttons below; everything from the title on sits centered in the remaining space (equal
                // room above and below). With lyrics, they get all the space and the title block moves down.
                // On tablets in portrait, don't stretch across the full width.
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
                                LyricsPane(lyrics = lyrics?.lyrics, fromLrclib = lyrics?.fromLrclib == true, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
                            }
                        } else {
                            // Square in the remaining space, with some room to the header and the button row.
                            FitSquare(
                                Modifier.weight(1f, fill = false).fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
                                maxSide = 420.dp,
                            ) { fit ->
                                AlbumArt(albumId = current.albumId, modifier = fit, shape = MaterialTheme.shapes.extraLarge)
                            }
                        }
                        actionRow()
                        SleepTimerLine(sleepTimerRemainingMs, toEnd = SleepTimerEnd.SONG.takeIf { sleepTimerToEnd })
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

/** Below this (and wider than tall), now playing uses two columns instead of the cover above the buttons. */
internal val ShortHeight = 480.dp

/**
 * Header of all playback screens (music, books, episodes): close on the left, [actions] on the right (share, for
 * books and episodes also the watch). No title – the player itself shows that something is playing.
 */
@Composable
internal fun PlayerTopBar(closeLabel: String, onClose: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Box(Modifier.fillMaxWidth().height(48.dp)) {
        IconButton(onClick = onClose, modifier = Modifier.align(Alignment.CenterStart)) {
            Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = closeLabel)
        }
        Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** Remaining time of the sleep timer; if it runs until the end ([toEnd]: chapter or episode), that is shown in front. */
@Composable
internal fun SleepTimerLine(remainingMs: Long?, toEnd: SleepTimerEnd? = null) {
    if (remainingMs == null) return
    val text = when (toEnd) {
        SleepTimerEnd.CHAPTER -> R.string.sleep_timer_until_chapter_end
        SleepTimerEnd.EPISODE -> R.string.sleep_timer_until_episode_end
        SleepTimerEnd.BOOK -> R.string.sleep_timer_until_book_end
        SleepTimerEnd.SONG -> R.string.sleep_timer_until_song_end
        null -> R.string.sleep_timer_remaining
    }
    // Separate line without truncation so the remaining time is fully readable even with long durations and large font.
    Text(
        stringResource(text, formatDuration(remainingMs)),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Title, artist, album, cast hint, progress and the playback buttons. */
@Composable
private fun TrackInfoAndControls(
    current: QueueEntry,
    state: PlayerState,
    positionMs: () -> Long,
    castDeviceName: String?,
    links: PlayerLinks,
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
        // Artist and album lead to their pages, like the names on the album page.
        ArtistPicker(lookup = { links.artistsOf(current) }, onArtistClick = links.onOpenArtist) { openArtist ->
            Text(
                current.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.linkClick(stringResource(R.string.action_go_to_artist), openArtist),
            )
        }
        if (current.albumTitle.isNotEmpty()) {
            val albumId = current.albumId
            Text(
                current.albumTitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = if (albumId != null) {
                    Modifier.linkClick(stringResource(R.string.action_go_to_album)) { links.onOpenAlbum(albumId) }
                } else {
                    Modifier
                },
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
 * Two children: cover with buttons at the very top, then the title block with half of the remaining space before and
 * after it, so the free space above and below is equal.
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
    // While dragging, show the finger position, not the (delayed) player position.
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

/**
 * ⋮ at the top right next to share: go to album/artist, mix from the current song (it keeps playing, the mix replaces what comes after it), current
 * song to a playlist, save queue as playlist, equalizer (only if there is one), track info.
 */
@Composable
private fun PlayerMoreMenu(state: PlayerState, links: PlayerLinks) {
    val current = state.current ?: return
    var open by remember { mutableStateOf(false) }
    var playlistSongs by remember { mutableStateOf<List<Long>?>(null) }
    var savingQueue by remember { mutableStateOf(false) }
    var infoVisible by remember { mutableStateOf(false) }
    val openEqualizer = rememberEqualizerLauncher(state.isRemote)
    ArtistPicker(lookup = { links.artistsOf(current) }, onArtistClick = links.onOpenArtist) { openArtist ->
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            current.albumId?.let { albumId ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_go_to_album)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_album), contentDescription = null) },
                    onClick = {
                        open = false
                        links.onOpenAlbum(albumId)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_go_to_artist)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_person), contentDescription = null) },
                onClick = {
                    open = false
                    openArtist()
                },
            )
            MixMenuItem(MixSeed.Song(current.songId), onDismiss = { open = false }, afterCurrent = true)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_to_playlist)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = null) },
                onClick = {
                    open = false
                    playlistSongs = listOf(current.songId)
                },
            )
            if (state.queue.size > 1) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.queue_save_as_playlist)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
                    onClick = {
                        open = false
                        savingQueue = true
                    },
                )
            }
            if (openEqualizer != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_equalizer)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_equalizer), contentDescription = null) },
                    onClick = {
                        open = false
                        openEqualizer()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.track_info_title)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_info), contentDescription = null) },
                onClick = {
                    open = false
                    infoVisible = true
                },
            )
        }
    }
    }
    playlistSongs?.let { AddToPlaylistDialog(songIds = it, onDismiss = { playlistSongs = null }) }
    if (savingQueue) SaveQueueDialog(onDismiss = { savingQueue = false })
    // Follows the current title, even if it changes while being shown.
    if (infoVisible) TrackInfoSheet(songId = current.songId, title = current.title, onDismiss = { infoVisible = false })
}

/** Where the names in the player lead; [None] in previews. */
internal class PlayerLinks(
    val onOpenAlbum: (Long) -> Unit,
    val onOpenArtist: (Long) -> Unit,
    val artistsOf: suspend (QueueEntry) -> List<Artist>,
) {
    companion object {
        val None = PlayerLinks(onOpenAlbum = {}, onOpenArtist = {}, artistsOf = { emptyList() })
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
    EarLoftTheme {
        NowPlayingContent(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "A Song", artist = "A Band", albumId = null, albumTitle = "An Album"),
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
