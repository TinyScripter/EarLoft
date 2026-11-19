package de.kleinabersmart.earloft.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.LyricLine
import de.kleinabersmart.earloft.core.model.Lyrics

/**
 * Lyrics instead of the cover. [lyrics] is `null` while loading. Synced lines
 * follow playback and seek to their position when tapped. If the text comes from LRCLIB ([fromLrclib]),
 * the source is shown below.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics?,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    fromLrclib: Boolean = false,
) {
    when (lyrics) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Lyrics.None -> Hint(stringResource(R.string.lyrics_none), modifier)
        Lyrics.Unavailable -> Hint(stringResource(R.string.lyrics_unavailable), modifier, retry = onRetry)
        is Lyrics.Plain -> Column(
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        ) {
            Text(lyrics.text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            if (fromLrclib) SourceNote()
        }
        is Lyrics.Synced -> SyncedLyrics(lyrics.lines, fromLrclib, positionMs, onSeek, modifier)
    }
}

/** Source at the end of a text from the internet. */
@Composable
private fun SourceNote() {
    Text(
        stringResource(R.string.lyrics_source_lrclib),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp),
    )
}

@Composable
private fun Hint(text: String, modifier: Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.lyrics_retry)) }
    }
}

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, fromLrclib: Boolean, positionMs: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    // Recompose only when the active line changes, not on every position tick.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.startMs <= currentPositionMs() } } }
    val listState = rememberLazyListState()
    // The active line is in second place, the previous one stays visible above it; bottom padding allows this for the last lines too.
    BoxWithConstraints(modifier.fillMaxSize()) {
        LaunchedEffect(lines, active) {
            if (active >= 0) listState.animateScrollToItem((active - 1).coerceAtLeast(0))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 8.dp, bottom = maxHeight),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                val isActive = index == active
                Text(
                    line.text.ifBlank { "♪" },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSeek(line.startMs) }
                        .padding(vertical = 6.dp, horizontal = 8.dp),
                )
            }
            if (fromLrclib) item(key = "source") { SourceNote() }
        }
    }
}

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun SyncedLyricsPreview() {
    EarLoftTheme {
        LyricsPane(
            lyrics = Lyrics.Synced(listOf(LyricLine(0, "Moonlight falls on the roof"), LyricLine(5_000, "Moths are dancing softly"), LyricLine(10_000, "The city holds its breath"))),
            fromLrclib = true,
            positionMs = { 6_000 },
            onSeek = {},
            onRetry = {},
        )
    }
}
