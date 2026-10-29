package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.tooling.preview.Preview
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
    val position by rememberPlaybackPosition { viewModel.currentPositionMs }
    val lyricsVisible by viewModel.lyricsVisible.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    AlbumColorTheme(seedColor) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            NowPlayingContent(
                state = state,
                isFavorite = isFavorite,
                positionMs = position,
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
                castDeviceName = rememberCastDeviceName(state.isRemote),
                castButton = { CastButton() },
            )
        }
    }
}

@Composable
private fun NowPlayingContent(
    state: PlayerState,
    isFavorite: Boolean,
    positionMs: Long,
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
    modifier: Modifier = Modifier,
    castDeviceName: String? = null,
    castButton: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = stringResource(R.string.player_close))
            }
            Text(
                stringResource(R.string.player_now_playing),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            castButton()
            if (state.current != null) {
                ToggleIconButton(
                    icon = if (isFavorite) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border,
                    active = isFavorite,
                    label = stringResource(if (isFavorite) R.string.action_remove_favorite else R.string.action_add_favorite),
                    onClick = onToggleFavorite,
                )
            }
            if (state.current != null) {
                ToggleIconButton(
                    icon = DesignR.drawable.ic_lyrics,
                    active = lyricsVisible,
                    label = stringResource(if (lyricsVisible) R.string.player_hide_lyrics else R.string.player_show_lyrics),
                    onClick = onToggleLyrics,
                )
            }
            IconButton(onClick = onOpenQueue) {
                Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = stringResource(R.string.player_open_queue))
            }
        }

        val current = state.current
        if (current == null) {
            EmptyHint(stringResource(R.string.player_nothing_playing))
            return@Column
        }

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            if (lyricsVisible) {
                LyricsPane(lyrics = lyrics, positionMs = positionMs, onSeek = onSeek, onRetry = onRetryLyrics)
            } else {
                AlbumArt(
                    albumId = current.albumId,
                    modifier = Modifier
                        .widthIn(max = 420.dp)
                        .fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
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
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SeekBar(durationMs: Long, positionMs: Long, onSeek: (Long) -> Unit) {
    // Während des Ziehens die Fingerposition zeigen, nicht die (verzögerte) Player-Position.
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
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
            val shownMs = dragFraction?.let { (it * durationMs).toLong() } ?: positionMs
            Text(formatDuration(shownMs), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(durationMs), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ToggleIconButton(icon: Int, active: Boolean, label: String, onClick: () -> Unit) {
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
@Composable
private fun NowPlayingContentPreview() {
    KleinaberlautTheme {
        NowPlayingContent(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "Ein Lied", artist = "Eine Band", albumId = null),
                isPlaying = true,
                durationMs = 200_000,
                shuffle = true,
                repeatMode = Player.REPEAT_MODE_ALL,
            ),
            isFavorite = true,
            positionMs = 80_000,
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
        )
    }
}
