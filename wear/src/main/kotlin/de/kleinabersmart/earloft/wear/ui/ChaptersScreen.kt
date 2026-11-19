package de.kleinabersmart.earloft.wear.ui

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
import de.kleinabersmart.earloft.core.model.BookChapter
import de.kleinabersmart.earloft.wear.R

/** Chapters of the book or episode the phone is playing (remote control); a tap seeks on the phone. */
@Composable
fun RemoteChaptersScreen(onDone: () -> Unit, viewModel: RemotePlayerViewModel = hiltViewModel()) = ChaptersScreen(onDone, viewModel)

/**
 * Chapters of the current book or episode (from the now playing view): the current one highlighted and in view when
 * opened; a tap seeks to the start of the chapter, [onDone] leads back to playback. Chapters in a file that is no longer
 * available ([BookChapter.unavailable]) are grayed out; the player skips them. [viewModel] is the watch itself
 * or, in remote control, the phone ([RemotePlayerViewModel]).
 */
@Composable
fun ChaptersScreen(onDone: () -> Unit, viewModel: ChaptersModel = hiltViewModel<PlayerViewModel>()) {
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val current by viewModel.currentChapter.collectAsStateWithLifecycle()
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var scrolled by remember { mutableStateOf(false) }
    // Once to the current chapter; after that only turning the crown scrolls.
    LaunchedEffect(chapters, current) {
        val index = chapters.indexOfFirst { it.index == current?.index }
        if (!scrolled && index >= 0) {
            // +1 for the heading.
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
                    enabled = !chapter.unavailable,
                    colors = if (active) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
                    label = { Text(chapter.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(if (chapter.unavailable) stringResource(R.string.player_chapter_gone) else formatPosition(chapter.startMs))
                    },
                )
            }
        }
    }
}
