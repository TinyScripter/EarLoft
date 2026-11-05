package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.wear.R
import kotlinx.coroutines.launch

/** Die geladenen Alben der Uhr; mit [artistId] nur die dieses Künstlers, dann steht [title] (sein Name) oben. */
@Composable
fun LocalAlbumsScreen(
    onClick: (Long) -> Unit,
    artistId: Long? = null,
    title: String? = null,
    viewModel: LocalAlbumsViewModel = hiltViewModel<LocalAlbumsViewModel, LocalAlbumsViewModel.Factory> { it.create(artistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(title ?: stringResource(R.string.list_albums), state, onClick)
}

@Composable
fun LocalArtistsScreen(onClick: (artistId: Long, name: String) -> Unit, viewModel: LocalArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_artists), state) { id -> onClick(id, state.titleOf(id)) }
}

private fun EntryListState.titleOf(key: Long): String = (this as? EntryListState.Ready)?.entries?.firstOrNull { it.key == key }?.title.orEmpty()

@Composable
fun LocalPlaylistsScreen(onClick: (Long) -> Unit, viewModel: LocalPlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_playlists), state, onClick)
}

/** Die Alben des Handys; mit [artistId] nur die dieses Künstlers, dann steht [title] (sein Name) oben. */
@Composable
fun RemoteAlbumsScreen(
    onClick: (Long) -> Unit,
    artistId: Long? = null,
    title: String? = null,
    viewModel: RemoteAlbumsViewModel = hiltViewModel<RemoteAlbumsViewModel, RemoteAlbumsViewModel.Factory> { it.create(artistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(title ?: stringResource(R.string.list_albums), state, onClick)
}

@Composable
fun RemoteArtistsScreen(onClick: (artistId: Long, name: String) -> Unit, viewModel: RemoteArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_artists), state) { id -> onClick(id, state.titleOf(id)) }
}

@Composable
fun RemotePlaylistsScreen(onClick: (Long) -> Unit, viewModel: RemotePlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_playlists), state, onClick)
}

/** Podcasts ([BookKind.EPISODE]) bzw. Bücher auf der Uhr; ein Tipp spielt hier, [onPlayed] öffnet danach die Wiedergabe-Ansicht. */
@Composable
fun LocalSpokenScreen(
    kind: BookKind,
    onPlayed: () -> Unit,
    viewModel: LocalSpokenViewModel = hiltViewModel<LocalSpokenViewModel, LocalSpokenViewModel.Factory> { it.create(kind) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val title = stringResource(if (kind == BookKind.EPISODE) R.string.list_podcasts else R.string.list_books)
    EntryListContent(title, state) { bookId ->
        scope.launch { if (viewModel.play(bookId)) onPlayed() }
    }
}

/** „Weiterhören“ des Handys; ein Tipp startet das Buch bzw. die Folge dort, [onPlayed] öffnet danach die Wiedergabe-Ansicht. */
@Composable
fun RemoteContinueScreen(onPlayed: () -> Unit, viewModel: RemoteContinueViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    EntryListContent(stringResource(R.string.list_continue), state) { bookId ->
        scope.launch { if (viewModel.play(bookId)) onPlayed() }
    }
}

@Composable
private fun EntryListContent(title: String, state: EntryListState, onClick: (Long) -> Unit) {
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
                    Text(title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
                    val cover = entry.cover
                    Button(
                        onClick = { onClick(entry.key) },
                        enabled = entry.enabled,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = if (cover != null) { { CoverThumbnail(cover) } } else null,
                        label = { Text(entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = {
                            val loaded = entry.loadedCount
                            Text(
                                when {
                                    !entry.enabled -> stringResource(R.string.selection_not_loaded)
                                    loaded != null && loaded < entry.songCount ->
                                        stringResource(R.string.selection_progress, loaded, entry.songCount)
                                    entry.artist != null -> entry.artist
                                    entry.albumCount != null -> pluralStringResource(R.plurals.album_count, entry.albumCount, entry.albumCount)
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
            "Alben",
            EntryListState.Ready(
                listOf(
                    ListEntry(1, "Mondlicht", 4, artist = "Nachtfalter", loadedCount = 4, cover = CoverRef.Album(1)),
                    ListEntry(2, "Ein Album mit sehr langem Namen", 12, artist = "Jemand", loadedCount = 5, cover = CoverRef.Album(2)),
                ),
            ),
            onClick = {},
        )
    }
}
