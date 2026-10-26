package keinabersmart.kleinaberlaut.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.components.AlbumCard
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.NoSongActions
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.components.PreviewSong
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem

@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HomeContent(
        state = state,
        onAlbumClick = onAlbumClick,
        onSongClick = viewModel::playRecentlyPlayed,
        actions = viewModel,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeContent(
    state: HomeUiState,
    onAlbumClick: (Long) -> Unit,
    onSongClick: (Int) -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.app_name)) })
        if (state.isSyncing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.isEmpty) {
            EmptyHint(stringResource(if (state.isSyncing) R.string.library_syncing else R.string.library_empty))
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize()) {
            if (state.recentlyAdded.isNotEmpty()) {
                item(key = "added-header") { SectionHeader(stringResource(R.string.home_recently_added)) }
                item(key = "added") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(state.recentlyAdded, key = { it.id }) { album ->
                            AlbumCard(album = album, onClick = { onAlbumClick(album.id) }, width = 144.dp)
                        }
                    }
                }
            }
            if (state.recentlyPlayed.isNotEmpty()) {
                item(key = "played-header") { SectionHeader(stringResource(R.string.home_recently_played)) }
                itemsIndexed(state.recentlyPlayed, key = { _, song -> "played-${song.id}" }) { index, song ->
                    SongListItem(song = song, onClick = { onSongClick(index) }, actions = actions)
                }
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
                recentlyPlayed = List(3) { PreviewSong.copy(id = it.toLong()) },
                recentlyAdded = List(4) { PreviewAlbum.copy(id = it.toLong()) },
                isLoading = false,
            ),
            onAlbumClick = {},
            onSongClick = {},
            actions = NoSongActions,
        )
    }
}
