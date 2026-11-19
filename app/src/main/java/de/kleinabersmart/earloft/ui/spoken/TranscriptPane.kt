package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.TranscriptResult
import de.kleinabersmart.earloft.core.model.Transcript
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Transkript einer Folge im Player (Konzept 12.5 „Transkripte“). [result] ist `null`, solange es lädt. Hat es Zeiten,
 * läuft es mit der Wiedergabe mit, und ein Tipp auf einen Absatz springt dorthin; wer selbst scrollt, hält das Mitlaufen
 * an, bis er „Zur aktuellen Stelle“ tippt. Die Suche zeigt nur die passenden Absätze.
 */
@Composable
internal fun TranscriptPane(
    result: TranscriptResult?,
    positionMs: () -> Long,
    onSeek: (Long) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when (result) {
        null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        TranscriptResult.None -> TranscriptHint(stringResource(R.string.transcript_none), modifier)
        TranscriptResult.Unavailable -> TranscriptHint(stringResource(R.string.transcript_unavailable), modifier, onRetry)
        is TranscriptResult.Ready -> TranscriptText(result.transcript, positionMs, onSeek, modifier)
    }
}

@Composable
private fun TranscriptHint(text: String, modifier: Modifier, retry: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (retry != null) TextButton(onClick = retry) { Text(stringResource(R.string.lyrics_retry)) }
    }
}

@Composable
private fun TranscriptText(transcript: Transcript, positionMs: () -> Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    val segments = transcript.segments
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val shown = remember(segments, needle) {
        segments.withIndex().filter { needle.isEmpty() || it.value.text.contains(needle, ignoreCase = true) }
    }
    // Nur wenn der laufende Absatz wechselt, wird neu zusammengesetzt, nicht bei jedem Positions-Tick.
    val currentPositionMs by rememberUpdatedState(positionMs)
    val active by remember(segments) {
        derivedStateOf {
            if (!transcript.timed) -1 else segments.indexOfLast { segment -> segment.startMs?.let { it <= currentPositionMs() } == true }
        }
    }
    var follow by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) follow = false }
    }
    // Wie beim Songtext: Der laufende Absatz steht an zweiter Stelle, der vorige bleibt sichtbar.
    LaunchedEffect(active, follow, needle) {
        if (follow && needle.isEmpty() && active >= 0) listState.animateScrollToItem((active - 1).coerceAtLeast(0))
    }
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    val focus = LocalFocusManager.current

    Column(modifier) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text(stringResource(R.string.transcript_search)) },
            leadingIcon = { Icon(painterResource(R.drawable.ic_nav_search), contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 72.dp)) {
                items(shown, key = { it.index }) { (index, segment) ->
                    val start = segment.startMs
                    val isActive = index == active && needle.isEmpty()
                    // Der Sprecher steht über dem ersten Absatz, wenn er wechselt; Suchtreffer nennen auch die Zeit.
                    val label = listOfNotNull(
                        segment.speaker?.takeIf { needle.isNotEmpty() || segments.getOrNull(index - 1)?.speaker != it },
                        start?.takeIf { needle.isNotEmpty() }?.let(::formatClock),
                    ).joinToString(" · ")
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isActive) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .then(
                                if (start != null) {
                                    Modifier.clickable {
                                        onSeek(start)
                                        focus.clearFocus()
                                        query = ""
                                        follow = true
                                    }
                                } else {
                                    Modifier
                                },
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        if (label.isNotEmpty()) {
                            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(
                            highlighted(segment.text, needle, highlight),
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (transcript.timed && !isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                if (shown.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            stringResource(R.string.search_no_results, needle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            // Der Griff springt per `scrollToItem`, das meldet kein Ziehen; das Mitlaufen endet hier wie beim Wischen.
            LazyListFastScroller(listState, onDragStart = { follow = false })
            if (!follow && needle.isEmpty() && transcript.timed) {
                FilledTonalButton(onClick = { follow = true }, modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp)) {
                    Text(stringResource(R.string.transcript_follow))
                }
            }
        }
    }
}

/** [text] mit allen Vorkommen von [needle] (ohne Rücksicht auf Groß-/Kleinschreibung) hinterlegt. */
private fun highlighted(text: String, needle: String, color: Color): AnnotatedString {
    if (needle.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var from = 0
        while (true) {
            val at = text.indexOf(needle, from, ignoreCase = true)
            if (at < 0) break
            append(text, from, at)
            withStyle(SpanStyle(background = color)) { append(text, at, at + needle.length) }
            from = at + needle.length
        }
        append(text, from, text.length)
    }
}
