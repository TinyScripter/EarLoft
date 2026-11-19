package de.kleinabersmart.earloft.wear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import de.kleinabersmart.earloft.wear.R

/** A track of the list; [key] is the entry for playlists (duplicates stay distinguishable), the position for the phone. */
data class TrackItem(val key: Long, val title: String, val artist: String, val enabled: Boolean = true)

/** `Loading` before the first value; `Message` if there is nothing to show (list removed, phone does not answer). */
sealed interface TrackListState {
    data object Loading : TrackListState

    data class Message(@StringRes val textRes: Int) : TrackListState

    /**
     * [loadedCount] = downloaded tracks on the watch; `null` for the phone's list (everything is playable there).
     * [truncated]: the phone sent only the first tracks.
     */
    data class Ready(
        val title: String,
        val songCount: Int,
        val loadedCount: Int?,
        val items: List<TrackItem>,
        val truncated: Boolean = false,
    ) : TrackListState {
        val canPlay: Boolean get() = loadedCount == null || loadedCount > 0
    }
}

/**
 * Track list (Wear concept 11, 14): play ([playLabel]), shuffle, for phone lists the "Download" button
 * ([onWatch], Wear concept 17; removal with confirmation) and the tracks. Unplayable tracks are grayed out; [onPlay] gets the key of the
 * tapped track or `null` for "from the start".
 */
@Composable
fun TrackListContent(
    state: TrackListState,
    onPlay: (key: Long?, shuffle: Boolean) -> Unit,
    @StringRes playLabel: Int = R.string.selection_play,
    onWatch: OnWatchState? = null,
    onToggleWatch: (Boolean) -> Unit = {},
) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    var confirmRemove by remember { mutableStateOf(false) }
    RemoveConfirmDialog(
        visible = confirmRemove,
        name = (state as? TrackListState.Ready)?.title.orEmpty(),
        onConfirm = {
            confirmRemove = false
            onToggleWatch(false)
        },
        onDismiss = { confirmRemove = false },
    )
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        if (state !is TrackListState.Ready) {
            // Loading indicator and message are in the center of the screen, not at the start of the list.
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                when (state) {
                    is TrackListState.Message -> Text(stringResource(state.textRes), textAlign = TextAlign.Center)
                    else -> CircularProgressIndicator()
                }
            }
            return@ScreenScaffold
        }
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            if (state.title.isNotEmpty()) {
                item {
                    ListHeader(
                        modifier = Modifier.transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                    ) {
                        Text(state.title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                val loaded = state.loadedCount
                Text(
                    if (loaded == null || loaded >= state.songCount) {
                        pluralStringResource(R.plurals.song_count, state.songCount, state.songCount)
                    } else {
                        stringResource(R.string.selection_progress, loaded, state.songCount)
                    },
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (state.canPlay) {
                item {
                    Button(
                        onClick = { onPlay(null, false) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(stringResource(playLabel)) },
                    )
                }
                item {
                    Button(
                        onClick = { onPlay(null, true) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(stringResource(R.string.selection_shuffle)) },
                    )
                }
            }
            if (onWatch != null) onWatchButton(onWatch, { onToggleWatch(true) }, { confirmRemove = true }, transformationSpec)
            items(state.items, key = { it.key }) { track ->
                Button(
                    onClick = { onPlay(track.key, false) },
                    enabled = track.enabled,
                    modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                    label = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        val text = if (track.enabled) track.artist else stringResource(R.string.selection_not_loaded)
                        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                )
            }
            if (state.truncated) {
                item {
                    Text(
                        stringResource(R.string.remote_truncated),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
