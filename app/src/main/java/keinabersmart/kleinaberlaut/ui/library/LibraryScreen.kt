package keinabersmart.kleinaberlaut.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.ui.components.AlbumCard
import keinabersmart.kleinaberlaut.ui.components.ArtistListItem
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

private enum class LibraryTab(val label: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists),
    SONGS(R.string.library_songs),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_library)) })
        PrimaryTabRow(selectedTabIndex = selectedTab) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(stringResource(tab.label)) },
                )
            }
        }
        when (LibraryTab.entries[selectedTab]) {
            LibraryTab.ALBUMS -> Loadable(albums) { AlbumGrid(it, onAlbumClick) }
            LibraryTab.ARTISTS -> Loadable(artists) { ArtistList(it, onArtistClick) }
            LibraryTab.SONGS -> Loadable(songs) {
                SongList(
                    songs = it,
                    onSongClick = viewModel::playSongs,
                    onShuffle = viewModel::shuffleSongs,
                    actions = viewModel,
                )
            }
        }
    }
}

@Composable
private fun <T> Loadable(items: List<T>?, content: @Composable (List<T>) -> Unit) {
    when {
        items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        items.isEmpty() -> EmptyHint(stringResource(R.string.library_empty))
        else -> content(items)
    }
}

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(12.dp),
        modifier = modifier.fillMaxSize(),
    ) {
        items(albums, key = { it.id }) { album ->
            AlbumCard(album = album, onClick = { onAlbumClick(album.id) })
        }
    }
}

@Composable
private fun ArtistList(artists: List<Artist>, onArtistClick: (Long) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(artists, key = { it.id }) { artist ->
            ArtistListItem(artist = artist, onClick = { onArtistClick(artist.id) })
        }
    }
}

@Composable
private fun SongList(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "shuffle") {
            FilledTonalButton(onClick = onShuffle, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null)
                Text(stringResource(R.string.action_shuffle), modifier = Modifier.padding(start = 8.dp))
            }
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongListItem(song = song, onClick = { onSongClick(index) }, actions = actions)
        }
    }
}
