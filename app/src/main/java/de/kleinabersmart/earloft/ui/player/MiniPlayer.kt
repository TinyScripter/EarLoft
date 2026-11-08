package de.kleinabersmart.earloft.ui.player

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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.playback.PlayerState
import de.kleinabersmart.earloft.core.playback.QueueEntry
import de.kleinabersmart.earloft.ui.components.AlbumArt
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@Composable
fun MiniPlayer(
    state: PlayerState,
    positionProvider: () -> Long,
    positionJumps: Flow<Unit>,
    onClick: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val current = state.current ?: return
    val position by rememberPlaybackPosition(state.isPlaying, positionJumps, current, positionProvider = positionProvider)

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
    EarLoftTheme {
        MiniPlayer(
            state = PlayerState(
                current = QueueEntry(songId = 1, title = "Ein Lied", artist = "Eine Band", albumId = null),
                isPlaying = true,
                durationMs = 200_000,
            ),
            positionProvider = { 80_000 },
            positionJumps = emptyFlow(),
            onClick = {},
            onTogglePlayPause = {},
            onNext = {},
        )
    }
}
