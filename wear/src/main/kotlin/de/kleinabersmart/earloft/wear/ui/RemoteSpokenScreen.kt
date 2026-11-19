package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.launch

/**
 * An episode or book of the phone (Wear concept 17): play on the phone or bring it to the watch with "Download"
 * ([RemoteSpokenViewModel]). [onPlayed] switches to the phone's now playing view after starting.
 */
@Composable
fun RemoteSpokenScreen(
    item: WearRemoteBookDto,
    onPlayed: () -> Unit,
    viewModel: RemoteSpokenViewModel = hiltViewModel<RemoteSpokenViewModel, RemoteSpokenViewModel.Factory> { it.create(item) },
) {
    val onWatch by viewModel.onWatch.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    RemoteSpokenContent(
        item = item,
        onWatch = onWatch,
        onPlay = { scope.launch { if (viewModel.play()) onPlayed() } },
        onSetOnWatch = viewModel::setOnWatch,
    )
}

@Composable
private fun RemoteSpokenContent(item: WearRemoteBookDto, onWatch: OnWatchState, onPlay: () -> Unit, onSetOnWatch: (Boolean) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var confirmRemove by remember { mutableStateOf(false) }
    RemoveConfirmDialog(
        visible = confirmRemove,
        name = item.title,
        onConfirm = {
            confirmRemove = false
            onSetOnWatch(false)
        },
        onDismiss = { confirmRemove = false },
    )
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(item.title, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            if (item.author.isNotBlank()) {
                item {
                    Text(
                        item.author,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(stringResource(R.string.remote_play_on_phone)) },
                )
            }
            onWatchButton(onWatch, { onSetOnWatch(true) }, { confirmRemove = true }, transformationSpec)
        }
    }
}

@WearPreviewDevices
@Composable
private fun RemoteSpokenPreview() {
    MaterialTheme {
        RemoteSpokenContent(
            item = WearRemoteBookDto(1, "#383 Drohnen überall – wie eine Waffe den Krieg verändert", "Was tun, Herr General?", "f1", "g1"),
            onWatch = OnWatchState(OnWatchStatus.DOWNLOADING, progress = 0.23f),
            onPlay = {},
            onSetOnWatch = {},
        )
    }
}
