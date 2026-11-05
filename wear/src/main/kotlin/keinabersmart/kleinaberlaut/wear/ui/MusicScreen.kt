package keinabersmart.kleinaberlaut.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.wear.R
import keinabersmart.kleinaberlaut.wear.download.SelectionItem
import keinabersmart.kleinaberlaut.wear.download.WatchStorageRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Musik auf der Uhr nach Auswahlen vom Handy (Wear-Konzept 11). */
data class MusicUiState(val selections: List<SelectionItem> = emptyList()) {
    /** Die Geräte-Playlist der Favoriten auf der Uhr; `null`, solange die Favoriten nicht übertragen sind. */
    val favoritesPlaylistId: Long? get() = selections.firstOrNull { it.kind == WearSelectionKind.FAVORITES }?.playlistId

    /** Ohne geladene Songs gibt es nichts zu mischen. */
    val canShuffleAll: Boolean get() = selections.any { it.downloadedCount > 0 }
}

@HiltViewModel
class MusicViewModel @Inject constructor(
    private val storage: WatchStorageRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {
    val state: StateFlow<MusicUiState> = storage.selections()
        .map { MusicUiState(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MusicUiState())

    /** Mischt alle geladenen Songs; [onStarted] wechselt danach zur Wiedergabe-Ansicht. */
    fun shuffleAll(onStarted: () -> Unit) {
        viewModelScope.launch {
            val songs = storage.downloadedSongs()
            if (songs.isEmpty()) return@launch
            playback.play(songs, shuffle = true)
            onStarted()
        }
    }
}

/**
 * Menü Musik der Uhr: Alles zufällig, Alben, Künstler, Favoriten und Playlists. Favoriten sind ausgegraut, solange sie
 * nicht auf der Uhr sind.
 */
@Composable
fun MusicScreen(
    onShuffled: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: (playlistId: Long) -> Unit,
    onPlaylistsClick: () -> Unit,
    viewModel: MusicViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    MusicContent(
        state = state,
        onShuffleAll = { viewModel.shuffleAll(onShuffled) },
        onAlbumsClick = onAlbumsClick,
        onArtistsClick = onArtistsClick,
        onFavoritesClick = onFavoritesClick,
        onPlaylistsClick = onPlaylistsClick,
    )
}

@Composable
private fun MusicContent(
    state: MusicUiState,
    onShuffleAll: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: (Long) -> Unit,
    onPlaylistsClick: () -> Unit,
) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            header(R.string.list_music, transformationSpec)
            if (state.canShuffleAll) action(R.string.start_shuffle_all, transformationSpec, onClick = onShuffleAll)
            action(R.string.list_albums, transformationSpec, onClick = onAlbumsClick)
            action(R.string.list_artists, transformationSpec, onClick = onArtistsClick)
            val favorites = state.favoritesPlaylistId
            action(R.string.list_favorites, transformationSpec, enabled = favorites != null, onClick = { favorites?.let(onFavoritesClick) })
            action(R.string.list_playlists, transformationSpec, onClick = onPlaylistsClick)
        }
    }
}

@WearPreviewDevices
@Composable
private fun MusicPreview() {
    MaterialTheme {
        MusicContent(
            state = MusicUiState(listOf(SelectionItem(1, WearSelectionKind.FAVORITES, "Favoriten", 42))),
            onShuffleAll = {},
            onAlbumsClick = {},
            onArtistsClick = {},
            onFavoritesClick = {},
            onPlaylistsClick = {},
        )
    }
}
