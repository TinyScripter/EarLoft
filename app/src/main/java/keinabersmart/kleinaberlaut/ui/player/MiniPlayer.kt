package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun MiniPlayer(
    state: PlayerState,
    positionProvider: () -> Long,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val position by rememberPlaybackPosition(positionProvider)

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Column {
            LinearProgressIndicator(
                progress = { if (state.durationMs > 0) (position.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth(),
                drawStopIndicator = {},
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            ) {
                AlbumArt(albumId = current.albumId, modifier = Modifier.size(44.dp), shape = MaterialTheme.shapes.small)
                Column(
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        current.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        current.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        painterResource(playPauseIcon(state.isPlaying)),
                        contentDescription = stringResource(playPauseLabel(state.isPlaying)),
                    )
                }
                IconButton(onClick = onNext) {
                    Icon(painterResource(DesignR.drawable.ic_skip_next), contentDescription = stringResource(R.string.action_next))
                }
            }
        }
    }
}

@Preview
@Composable
private fun MiniPlayerPreview() {
    KleinaberlautTheme {
        MiniPlayer(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "Ein Lied", artist = "Eine Band", albumId = null),
                isPlaying = true,
                durationMs = 200_000,
            ),
            positionProvider = { 80_000 },
            onClick = {},
            onTogglePlayPause = {},
            onNext = {},
        )
    }
}
