package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
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
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.ui.components.AlbumCard
import de.kleinabersmart.earloft.ui.components.GridTilePadding
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.groupedItemsIndexed
import de.kleinabersmart.earloft.ui.components.gridTile
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.ArtistAvatar
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.EmptyAreaHint
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.letterOf
import de.kleinabersmart.earloft.ui.components.SongActions
import de.kleinabersmart.earloft.ui.components.SongListItem
import de.kleinabersmart.earloft.ui.playlists.PlaylistIcon
import de.kleinabersmart.earloft.ui.playlists.PlaylistNameDialog
import de.kleinabersmart.earloft.ui.playlists.playlistLocation
import de.kleinabersmart.earloft.ui.valueOrNull
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

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
    onAddServer: () -> Unit,
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
    // Ohne Musik: an erster Stelle einen Server einrichten, darunter der Weg zum Ausblenden des Bereichs.
    val empty: @Composable () -> Unit = {
        EmptyAreaHint(SpokenMode.MUSIC, stringResource(R.string.library_empty), stringResource(R.string.home_setup_server), onAddServer)
    }

    Column(modifier.fillMaxSize()) {
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
                LibraryTab.ALBUMS -> LoadableList(albums, empty) { AlbumGrid(it, onAlbumClick, fastScroll = true) }
                LibraryTab.ARTISTS -> LoadableList(artists, empty) { ArtistGrid(it, onArtistClick) }
                LibraryTab.SONGS -> LoadableList(songs, empty) {
                    SongList(
                        songs = it,
                        onSongClick = viewModel::playSongs,
                        onShuffle = viewModel::shuffleSongs,
                        actions = viewModel,
                        fastScroll = true,
                    )
                }
                LibraryTab.PLAYLISTS -> PlaylistList(
                    favoriteCount = favoriteCount,
                    // „Neue Playlist“ und „Favoriten“ stehen immer da; die eigenen Playlists erscheinen, sobald sie geladen sind.
                    playlists = playlists.valueOrNull.orEmpty(),
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

    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
            group("playlists") {
                row("new") {
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
                row("favorites") {
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
                playlists.forEach { playlist ->
                    row(playlist.id) {
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
        }
        // Die ersten beiden Zeilen sind „Neue Playlist“ und „Favoriten“.
        LazyListFastScroller(state, labelAt = { index -> letterOf(playlists.getOrNull((index - 2).coerceAtLeast(0))?.name.orEmpty()) })
    }
}

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Mit Schnellscroller, wenn die Liste nach Titel sortiert ist. */
    fastScroll: Boolean = false,
) {
    val state = rememberLazyGridState()
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    Box(modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(albums, key = { it.id }) { album ->
                AlbumCard(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer),
                    onPlay = { playControl.playOrPauseAlbum(album.id) },
                    playing = album.id == playingAlbumId,
                    contentPadding = GridTilePadding,
                )
            }
        }
        if (fastScroll) {
            LazyGridFastScroller(state, labelAt = { index -> letterOf(albums.getOrNull(index)?.title.orEmpty().sortKey()) })
        }
    }
}

/** Künstler als Raster, zwei pro Reihe, aufgebaut wie die Autoren der Bücher: Bild, darunter Name und Zahl der Alben. */
@Composable
private fun ArtistGrid(artists: List<Artist>, onArtistClick: (Long) -> Unit) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(artists, key = { it.id }) { artist ->
                Column(
                    Modifier
                        .gridTile(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onArtistClick(artist.id) }
                        .padding(GridTilePadding),
                ) {
                    ArtistAvatar(artistId = artist.id, modifier = Modifier.fillMaxWidth(), iconSize = 64.dp)
                    Text(
                        artist.name,
                        style = MaterialTheme.typography.titleSmall,
                        minLines = 2,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        pluralStringResource(R.plurals.album_count, artist.albumCount, artist.albumCount),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyGridFastScroller(state, labelAt = { index -> letterOf(artists.getOrNull(index)?.name.orEmpty().sortKey()) })
    }
}

/** Songs mit „Zufällig“-Knopf oben; auch für Favoriten und Playlists. */
@Composable
fun SongList(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
    /** Mit Schnellscroller, wenn die Songs nach Titel sortiert sind. */
    fastScroll: Boolean = false,
) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "shuffle") {
                FilledTonalButton(onClick = onShuffle, modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp)) {
                    Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null)
                    Text(stringResource(R.string.action_shuffle), modifier = Modifier.padding(start = 8.dp))
                }
            }
            groupedItemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongListItem(song = song, onClick = { onSongClick(index) }, actions = actions)
            }
        }
        if (fastScroll) {
            // Index 0 ist der „Zufällig“-Knopf.
            LazyListFastScroller(state, labelAt = { index -> letterOf(songs.getOrNull(index - 1)?.title.orEmpty()) })
        }
    }
}
