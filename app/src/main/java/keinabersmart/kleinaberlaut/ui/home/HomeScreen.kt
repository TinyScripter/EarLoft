package keinabersmart.kleinaberlaut.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.components.AlbumCard
import keinabersmart.kleinaberlaut.ui.components.ArtistCard
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Image(
                    painter = painterResource(R.drawable.logo_wide),
                    contentDescription = stringResource(R.string.app_name),
                    modifier = Modifier.height(40.dp),
                )
            },
            actions = {
                IconButton(onClick = onOpenSettings) {
                    Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = stringResource(R.string.settings_title))
                }
            },
        )
        if (state.isSyncing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.isEmpty) {
            EmptyHint(stringResource(if (state.isSyncing) R.string.library_syncing else R.string.library_empty))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            albumSection("added", R.string.home_recently_added, state.recentlyAdded, onAlbumClick)
            albumSection("rediscover", R.string.home_rediscover, state.rediscover, onAlbumClick)
            albumSection("played", R.string.home_recently_played, state.recentlyPlayed, onAlbumClick)
            if (state.randomArtists.isNotEmpty()) {
                item(key = "artists-header") { SectionHeader(stringResource(R.string.home_random_artists)) }
                item(key = "artists") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.randomArtists, key = { it.id }) { artist ->
                            ArtistCard(artist = artist, onClick = { onArtistClick(artist.id) })
                        }
                    }
                }
            }
            albumSection("random", R.string.home_random_albums, state.randomAlbums, onAlbumClick)
        }
    }
}

/** Abschnitt mit Überschrift und horizontal scrollender Album-Reihe; leere Listen entfallen. */
private fun LazyListScope.albumSection(
    key: String,
    @StringRes title: Int,
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
) {
    if (albums.isEmpty()) return
    item(key = "$key-header") { SectionHeader(stringResource(title)) }
    item(key = key) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(album = album, onClick = { onAlbumClick(album.id) }, width = 144.dp)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    KleinaberlautTheme {
        HomeContent(
            state = HomeUiState(
                recentlyAdded = List(4) { PreviewAlbum.copy(id = it.toLong()) },
                rediscover = List(4) { PreviewAlbum.copy(id = 10L + it) },
                recentlyPlayed = List(4) { PreviewAlbum.copy(id = 20L + it) },
                randomArtists = List(4) { Artist(id = it.toLong(), name = "Eine Band", albumCount = 2) },
                randomAlbums = List(4) { PreviewAlbum.copy(id = 30L + it) },
                isLoading = false,
            ),
            onAlbumClick = {},
            onArtistClick = {},
            onOpenSettings = {},
        )
    }
}
