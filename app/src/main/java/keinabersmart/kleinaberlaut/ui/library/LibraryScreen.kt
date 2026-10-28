package keinabersmart.kleinaberlaut.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Playlist
import keinabersmart.kleinaberlaut.core.model.ServerInfo
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.ui.components.AlbumCard
import keinabersmart.kleinaberlaut.ui.components.ArtistListItem
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.ui.playlists.PlaylistIcon
import keinabersmart.kleinaberlaut.ui.playlists.PlaylistNameDialog
import keinabersmart.kleinaberlaut.ui.playlists.playlistLocation
import kotlinx.coroutines.launch
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

private enum class LibraryTab(val label: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists),
    SONGS(R.string.library_songs),
    PLAYLISTS(R.string.library_playlists),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onFavoritesClick: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    // Tabs und Seiten teilen sich den Pager-Zustand: Antippen und Wischen führen zum selben Ergebnis.
    val pagerState = rememberPagerState { LibraryTab.entries.size }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.nav_library)) })
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.label)) },
                )
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { it },
        ) { page ->
            when (LibraryTab.entries[page]) {
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
                LibraryTab.PLAYLISTS -> PlaylistList(
                    favoriteCount = favoriteCount,
                    playlists = playlists.orEmpty(),
                    servers = servers,
                    onFavoritesClick = onFavoritesClick,
                    onPlaylistClick = onPlaylistClick,
                    onCreate = { name, serverSourceId -> viewModel.createPlaylist(name, serverSourceId, onPlaylistClick) },
                )
            }
        }
    }
}

@Composable
private fun PlaylistList(
    favoriteCount: Int,
    playlists: List<Playlist>,
    servers: List<ServerInfo>?,
    onFavoritesClick: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onCreate: (name: String, serverSourceId: String?) -> Unit,
) {
    var creating by rememberSaveable { mutableStateOf(false) }
    if (creating && servers != null) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_new),
            confirmLabel = stringResource(R.string.playlist_create),
            servers = servers,
            onConfirm = { name, serverSourceId ->
                creating = false
                onCreate(name, serverSourceId)
            },
            onDismiss = { creating = false },
        )
    }

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "new") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.playlist_new)) },
                leadingContent = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                    }
                },
                modifier = Modifier.clickable { creating = true },
            )
        }
        item(key = "favorites") {
            ListItem(
                headlineContent = { Text(stringResource(R.string.favorites_title)) },
                supportingContent = { Text(pluralStringResource(R.plurals.song_count, favoriteCount, favoriteCount)) },
                leadingContent = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            painterResource(DesignR.drawable.ic_favorite),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                modifier = Modifier.clickable(onClick = onFavoritesClick),
            )
        }
        items(playlists, key = { it.id }) { playlist ->
            ListItem(
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = {
                    val songs = pluralStringResource(R.plurals.song_count, playlist.songCount, playlist.songCount)
                    Text("$songs · ${playlistLocation(playlist.serverName)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { PlaylistIcon() },
                modifier = Modifier.clickable { onPlaylistClick(playlist.id) },
            )
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

/** Songs mit „Zufällig“-Knopf oben; auch für Favoriten und Playlists. */
@Composable
fun SongList(
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
