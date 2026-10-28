package keinabersmart.kleinaberlaut.wear.ui

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
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
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.PlaylistRepository
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.model.PlaylistDetails
import keinabersmart.kleinaberlaut.core.model.PlaylistEntry
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.wear.R
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** `Loading` vor dem ersten Wert; `Missing`, wenn die Auswahl inzwischen entfernt wurde. */
sealed interface SelectionUiState {
    data object Loading : SelectionUiState

    data object Missing : SelectionUiState

    data class Ready(val details: PlaylistDetails) : SelectionUiState
}

@HiltViewModel(assistedFactory = SelectionViewModel.Factory::class)
class SelectionViewModel @AssistedInject constructor(
    @Assisted playlistId: Long,
    playlistRepository: PlaylistRepository,
) : ViewModel() {

    val state: StateFlow<SelectionUiState> = playlistRepository.playlist(playlistId)
        .map { details -> details?.let { SelectionUiState.Ready(it) } ?: SelectionUiState.Missing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SelectionUiState.Loading)

    @AssistedFactory
    interface Factory {
        fun create(playlistId: Long): SelectionViewModel
    }
}

/**
 * Eine Auswahl mit ihren Songs (Wear-Konzept 11). Bis Etappe 3c liegen noch keine Dateien auf der Uhr;
 * nicht geladene Songs sind ausgegraut und nicht antippbar.
 */
@Composable
fun SelectionScreen(
    playlistId: Long,
    viewModel: SelectionViewModel = hiltViewModel<SelectionViewModel, SelectionViewModel.Factory> { it.create(playlistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SelectionContent(state)
}

@Composable
private fun SelectionContent(state: SelectionUiState) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            when (state) {
                SelectionUiState.Loading -> item {
                    CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
                }
                SelectionUiState.Missing -> item {
                    Text(
                        stringResource(R.string.selection_missing),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                    )
                }
                is SelectionUiState.Ready -> {
                    val playlist = state.details.playlist
                    item {
                        ListHeader(
                            modifier = Modifier.transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                        ) {
                            Text(playlist.name, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    item {
                        Text(
                            pluralStringResource(R.plurals.song_count, playlist.songCount, playlist.songCount) + " · " +
                                stringResource(R.string.selection_not_loaded),
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(state.details.entries, key = { it.entryId }) { entry ->
                        Button(
                            onClick = {},
                            enabled = false,
                            modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = { Text(entry.song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            secondaryLabel = { Text(entry.song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        )
                    }
                }
            }
        }
    }
}

@WearPreviewDevices
@Composable
private fun SelectionPreview() {
    val songs = listOf("Aufbruch", "Mondlicht", "Nachtfalter").mapIndexed { index, title ->
        PlaylistEntry(index.toLong(), Song(index.toLong(), title, "Nachtfalter", 1, "Mondlicht", 200_000, 1, index + 1, false), false)
    }
    MaterialTheme {
        SelectionContent(SelectionUiState.Ready(PlaylistDetails(Playlist(1, "Mondlicht", songs.size, 600_000, null), songs)))
    }
}
