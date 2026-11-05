package de.kleinabersmart.tinyamp.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import de.kleinabersmart.tinyamp.wear.R

/**
 * Eine Auswahl mit ihren Songs (Wear-Konzept 11). Nicht geladene Songs sind ausgegraut und nicht antippbar;
 * [onPlayed] wird nach dem Start der Wiedergabe aufgerufen (Wechsel zur Wiedergabe-Ansicht).
 */
@Composable
fun SelectionScreen(playlistId: Long, onPlayed: () -> Unit) = LocalListScreen(LocalList.OfPlaylist(playlistId), onPlayed)

/** Ein geladenes Album der Uhr, sonst wie [SelectionScreen]. */
@Composable
fun AlbumScreen(albumId: Long, onPlayed: () -> Unit) = LocalListScreen(LocalList.OfAlbum(albumId), onPlayed)

@Composable
private fun LocalListScreen(
    source: LocalList,
    onPlayed: () -> Unit,
    viewModel: SelectionViewModel = hiltViewModel<SelectionViewModel, SelectionViewModel.Factory> { it.create(source) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TrackListContent(
        state = state.toTrackList(),
        onPlay = { entryId, shuffle ->
            viewModel.play(entryId, shuffle)
            onPlayed()
        },
    )
}

private fun SelectionUiState.toTrackList(): TrackListState = when (this) {
    SelectionUiState.Loading -> TrackListState.Loading
    SelectionUiState.Missing -> TrackListState.Message(R.string.selection_missing)
    is SelectionUiState.Ready -> TrackListState.Ready(
        title = details.playlist.name,
        songCount = details.playlist.songCount,
        loadedCount = downloadedCount,
        items = details.entries.map { TrackItem(it.entryId, it.song.title, it.song.artist, enabled = isLoaded(it.song.id)) },
    )
}

@WearPreviewDevices
@Composable
private fun SelectionPreview() {
    MaterialTheme {
        TrackListContent(
            state = TrackListState.Ready(
                title = "Mondlicht",
                songCount = 3,
                loadedCount = 2,
                items = listOf(
                    TrackItem(0, "Aufbruch", "Nachtfalter"),
                    TrackItem(1, "Mondlicht", "Nachtfalter"),
                    TrackItem(2, "Nachtfalter", "Nachtfalter", enabled = false),
                ),
            ),
            onPlay = { _, _ -> },
        )
    }
}
