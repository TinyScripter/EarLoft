package de.kleinabersmart.tinyamp.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import de.kleinabersmart.tinyamp.wear.R

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
    val favorites = state.favoritesPlaylistId
    MusicContent(
        canShuffleAll = state.canShuffleAll,
        favoritesEnabled = favorites != null,
        onShuffleAll = { viewModel.shuffleAll(onShuffled) },
        onAlbumsClick = onAlbumsClick,
        onArtistsClick = onArtistsClick,
        onFavoritesClick = { favorites?.let(onFavoritesClick) },
        onPlaylistsClick = onPlaylistsClick,
    )
}

/** Menü Musik des Handys (Bereich „Telefon“, Wear-Konzept 17): gegliedert wie das der Uhr, aber mit allem, was das Handy hat. */
@Composable
fun PhoneMusicScreen(
    onShuffled: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
    onPlaylistsClick: () -> Unit,
    viewModel: PhoneMusicViewModel = hiltViewModel(),
) {
    MusicContent(
        canShuffleAll = true,
        favoritesEnabled = true,
        onShuffleAll = { viewModel.shuffleAll(onShuffled) },
        onAlbumsClick = onAlbumsClick,
        onArtistsClick = onArtistsClick,
        onFavoritesClick = onFavoritesClick,
        onPlaylistsClick = onPlaylistsClick,
    )
}

@Composable
private fun MusicContent(
    canShuffleAll: Boolean,
    favoritesEnabled: Boolean,
    onShuffleAll: () -> Unit,
    onAlbumsClick: () -> Unit,
    onArtistsClick: () -> Unit,
    onFavoritesClick: () -> Unit,
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
            if (canShuffleAll) action(R.string.start_shuffle_all, transformationSpec, onClick = onShuffleAll)
            action(R.string.list_albums, transformationSpec, onClick = onAlbumsClick)
            action(R.string.list_artists, transformationSpec, onClick = onArtistsClick)
            action(R.string.list_favorites, transformationSpec, enabled = favoritesEnabled, onClick = onFavoritesClick)
            action(R.string.list_playlists, transformationSpec, onClick = onPlaylistsClick)
        }
    }
}

@WearPreviewDevices
@Composable
private fun MusicPreview() {
    MaterialTheme {
        MusicContent(
            canShuffleAll = true,
            favoritesEnabled = false,
            onShuffleAll = {},
            onAlbumsClick = {},
            onArtistsClick = {},
            onFavoritesClick = {},
            onPlaylistsClick = {},
        )
    }
}
