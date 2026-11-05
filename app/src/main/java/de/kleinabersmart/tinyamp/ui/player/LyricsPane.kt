package de.kleinabersmart.tinyamp.ui.player

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
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.designsystem.theme.TinyAmpTheme
import de.kleinabersmart.tinyamp.core.model.LyricLine
import de.kleinabersmart.tinyamp.core.model.Lyrics

/**
 * Songtext statt Cover. [lyrics] ist `null`, solange geladen wird. Synchronisierte Zeilen
 * folgen der Wiedergabe und springen beim Antippen an ihre Stelle.
 */
@Composable
fun LyricsPane(
    lyrics: Lyrics?,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (lyrics) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        Lyrics.None -> Hint(stringResource(R.string.lyrics_none), modifier)
        Lyrics.Unavailable -> Hint(stringResource(R.string.lyrics_unavailable), modifier, retry = onRetry)
        is Lyrics.Plain -> Text(
            lyrics.text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
        )
        is Lyrics.Synced -> SyncedLyrics(lyrics.lines, positionMs, onSeek, modifier)
    }
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
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    // Nur wenn die aktive Zeile wechselt, wird neu zusammengesetzt, nicht bei jedem Positions-Tick.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val active by remember(lines) { derivedStateOf { lines.indexOfLast { it.startMs <= currentPositionMs() } } }
    val listState = rememberLazyListState()
    // Die aktive Zeile steht an zweiter Stelle, davor bleibt die vorige sichtbar; Polster unten erlaubt das auch bei den letzten Zeilen.
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
        }
    }
}

@Preview(showBackground = true, heightDp = 400)
@Composable
private fun SyncedLyricsPreview() {
    TinyAmpTheme {
        LyricsPane(
            lyrics = Lyrics.Synced(listOf(LyricLine(0, "Mondlicht fällt aufs Dach"), LyricLine(5_000, "Nachtfalter tanzen leis"), LyricLine(10_000, "Die Stadt hält still"))),
            positionMs = { 6_000 },
            onSeek = {},
            onRetry = {},
        )
    }
}
