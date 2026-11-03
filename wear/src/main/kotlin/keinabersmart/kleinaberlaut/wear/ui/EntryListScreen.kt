package keinabersmart.kleinaberlaut.wear.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.material3.MaterialTheme
import keinabersmart.kleinaberlaut.wear.R

@Composable
fun LocalAlbumsScreen(onClick: (Long) -> Unit, viewModel: LocalAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_albums, state, onClick)
}

@Composable
fun LocalPlaylistsScreen(onClick: (Long) -> Unit, viewModel: LocalPlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_playlists, state, onClick)
}

@Composable
fun RemoteAlbumsScreen(onClick: (Long) -> Unit, viewModel: RemoteAlbumsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_albums, state, onClick)
}

@Composable
fun RemotePlaylistsScreen(onClick: (Long) -> Unit, viewModel: RemotePlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(R.string.list_playlists, state, onClick)
}

@Composable
private fun EntryListContent(@StringRes titleRes: Int, state: EntryListState, onClick: (Long) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
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
                    Text(stringResource(titleRes), textAlign = TextAlign.Center)
                }
            }
            when (state) {
                EntryListState.Loading -> item {
                    CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
                }
                is EntryListState.Message -> item {
                    Text(
                        stringResource(state.textRes),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is EntryListState.Ready -> items(state.entries, key = { it.key }) { entry ->
                    Button(
                        onClick = { onClick(entry.key) },
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            val loaded = entry.loadedCount
                            Text(
                                when {
                                    loaded != null && loaded < entry.songCount ->
                                        stringResource(R.string.selection_progress, loaded, entry.songCount)
                                    entry.artist != null -> entry.artist
                                    else -> pluralStringResource(R.plurals.song_count, entry.songCount, entry.songCount)
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun EntryListPreview() {
    MaterialTheme {
        EntryListContent(
            R.string.list_albums,
            EntryListState.Ready(
                listOf(
                    ListEntry(1, "Mondlicht", 4, artist = "Nachtfalter", loadedCount = 4),
                    ListEntry(2, "Ein Album mit sehr langem Namen", 12, artist = "Jemand", loadedCount = 5),
                ),
            ),
            onClick = {},
        )
    }
}
