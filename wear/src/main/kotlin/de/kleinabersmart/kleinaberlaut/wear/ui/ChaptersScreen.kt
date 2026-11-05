package de.kleinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.kleinabersmart.kleinaberlaut.wear.R

/**
 * Kapitel des laufenden Buchs bzw. der Folge (aus der Wiedergabe-Ansicht): das laufende hervorgehoben und beim Öffnen im
 * Blick; ein Tipp springt an den Beginn des Kapitels, [onDone] führt zurück zur Wiedergabe.
 */
@Composable
fun ChaptersScreen(onDone: () -> Unit, viewModel: PlayerViewModel = hiltViewModel()) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val current by viewModel.currentChapter.collectAsStateWithLifecycle()
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var scrolled by remember { mutableStateOf(false) }
    // Einmal zum laufenden Kapitel; danach scrollt nur noch, wer an der Krone dreht.
    LaunchedEffect(chapters, current) {
        val index = chapters.indexOfFirst { it.index == current?.index }
        if (!scrolled && index >= 0) {
            // +1 für die Überschrift.
            columnState.scrollToItem(index + 1)
            scrolled = true
        }
    }
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            line(transformationSpec, header = true) { stringResource(R.string.player_chapters) }
            items(chapters, key = { it.index }) { chapter ->
                val active = chapter.index == current?.index
                Button(
                    onClick = {
                        viewModel.seekToBook(chapter.startMs)
                        onDone()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .transformedHeight(this, transformationSpec)
                        .semantics { selected = active },
                    transformation = SurfaceTransformation(transformationSpec),
                    colors = if (active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(formatPosition(chapter.startMs)) },
                )
            }
        }
    }
}
