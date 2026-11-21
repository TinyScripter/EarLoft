package de.kleinabersmart.earloft.ui.library

import de.kleinabersmart.earloft.ui.playlists.PlaylistRowMenu
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
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
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.ArtistSort
import de.kleinabersmart.earloft.core.model.SongSort
import de.kleinabersmart.earloft.ui.components.HeaderAction
import de.kleinabersmart.earloft.ui.components.SortButton
import androidx.compose.foundation.layout.Row
import de.kleinabersmart.earloft.core.model.Artist
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.ServerInfo
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.ui.components.AlbumCard
import de.kleinabersmart.earloft.ui.components.GridTilePadding
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.groupedItemsIndexed
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.gridTile
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.ArtistAvatar
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.EmptyAreaHint
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.ui.components.PinnableHeaderBox
import de.kleinabersmart.earloft.ui.components.gridContentPadding
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.foldedLetterOf
import de.kleinabersmart.earloft.ui.components.SongActions
import de.kleinabersmart.earloft.ui.components.SongListItem
import de.kleinabersmart.earloft.ui.playlists.PlaylistIcon
import de.kleinabersmart.earloft.ui.playlists.PlaylistNameDialog
import de.kleinabersmart.earloft.ui.playlists.playlistLocation
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.valueOrNull
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

private enum class LibraryTab(val label: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists),
    SONGS(R.string.library_songs),
    GENRES(R.string.library_genres),
    PLAYLISTS(R.string.library_playlists),
    STATS(R.string.library_stats),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onGenreClick: (String) -> Unit,
    onFavoritesClick: () -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onAddServer: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LibraryViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val albumsOfDecades by viewModel.albumsOfDecades.collectAsStateWithLifecycle()
    val albumsOfGenres by viewModel.albumsOfGenres.collectAsStateWithLifecycle()
    val selectedDecades by viewModel.selectedDecades.collectAsStateWithLifecycle()
    val selectedGenres by viewModel.selectedGenres.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val artistsShown by viewModel.artistsShown.collectAsStateWithLifecycle()
    val favoriteAlbumIds by viewModel.favoriteAlbumIds.collectAsStateWithLifecycle()
    val favoriteArtistIds by viewModel.favoriteArtistIds.collectAsStateWithLifecycle()
    val favoritesOnly by viewModel.favoritesOnly.collectAsStateWithLifecycle()
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val songsShown by viewModel.songsShown.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val decades by viewModel.decades.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val songSort by viewModel.songSort.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    // The "Favorites" chip comes first as soon as there is something to show, and stays as long as it is on.
    val favoritesChip: (FavoritesChip, Set<Long>) -> FavoritesChipState? = { chip, ids ->
        val on = chip in favoritesOnly
        if (ids.isEmpty() && !on) null else FavoritesChipState(on) { viewModel.toggleFavoritesOnly(chip) }
    }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    // Tabs and pages share the pager state: tapping and swiping lead to the same result.
    val pagerState = rememberPagerState { LibraryTab.entries.size }
    val scope = rememberCoroutineScope()
    // Without music: setting up a server first, below it the way to hide the area.
    val empty: @Composable () -> Unit = {
        EmptyAreaHint(SpokenMode.MUSIC, stringResource(R.string.library_empty), stringResource(R.string.home_setup_server), onAddServer)
    }

    // The sort of the shown tab sits in the app header left of the magnifier; genres show albums, hence their sort.
    HeaderAction {
        when (LibraryTab.entries[pagerState.targetPage]) {
            LibraryTab.ALBUMS, LibraryTab.GENRES ->
                SortButton(albumSort, AlbumSort.entries, { stringResource(it.label) }, viewModel::setAlbumSort)
            LibraryTab.ARTISTS -> SortButton(artistSort, ArtistSort.entries, { stringResource(it.label) }, viewModel::setArtistSort)
            LibraryTab.SONGS -> SortButton(songSort, SongSort.entries, { stringResource(it.label) }, viewModel::setSongSort)
            LibraryTab.PLAYLISTS, LibraryTab.STATS -> {}
        }
    }

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            LibraryTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                ) { TabLabel(stringResource(tab.label)) }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            key = { it },
        ) { page ->
            when (LibraryTab.entries[page]) {
                // Empty means: no albums at all. If the selection leaves nothing, the chips stay so they can be cleared.
                // The header row is always there (empty if need be): if it only appeared with the first favorite, the grid would
                // keep the first tile anchored and push the chips out of view at the top.
                LibraryTab.ALBUMS -> LoadableList(albums, empty) { all ->
                    val shown = albumsOfDecades.valueOrNull ?: all
                    val favorites = favoritesChip(FavoritesChip.ALBUMS, favoriteAlbumIds)
                    AlbumGrid(
                        albums = shown,
                        onAlbumClick = onAlbumClick,
                        scrollLabel = albumSort.scrollLabel(),
                        pinHeader = true,
                        header = {
                            if (decades.isNotEmpty() || favorites != null) {
                                FilterHeader(
                                    chips = {
                                        ToggleChips(
                                            decades,
                                            { decade -> decade.startYear in selectedDecades },
                                            { decade -> decadeLabel(decade.startYear) },
                                            onToggle = { viewModel.toggleDecade(it.startYear) },
                                            favorites = favorites,
                                        )
                                    },
                                    filtered = selectedDecades.isNotEmpty() || FavoritesChip.ALBUMS in favoritesOnly,
                                    nothingShown = shown.isEmpty(),
                                    onPlay = { shuffle -> viewModel.playAlbums(shown, shuffle) },
                                )
                            }
                        },
                    )
                }
                LibraryTab.ARTISTS -> LoadableList(artists, empty) { all ->
                    val shown = artistsShown.valueOrNull ?: all
                    val favorites = favoritesChip(FavoritesChip.ARTISTS, favoriteArtistIds)
                    ArtistGrid(
                        artists = shown,
                        onArtistClick = onArtistClick,
                        byName = artistSort == ArtistSort.NAME,
                        pinHeader = true,
                        header = {
                            favorites?.let {
                                FilterHeader(
                                    chips = { ToggleChips(emptyList<Unit>(), { false }, { "" }, onToggle = {}, favorites = it) },
                                    filtered = it.selected,
                                    nothingShown = shown.isEmpty(),
                                    onPlay = { shuffle -> viewModel.playArtists(shown, shuffle) },
                                )
                            }
                        },
                    )
                }
                LibraryTab.SONGS -> LoadableList(songs, empty) { all ->
                    // The "Favorites" chip as on albums and artists (the song itself carries whether it is a favorite).
                    val favorites = favoritesChip(FavoritesChip.SONGS, if (favoriteCount > 0) setOf(0L) else emptySet())
                    SongList(
                        songs = songsShown.valueOrNull ?: all,
                        onSongClick = viewModel::playSongs,
                        onShuffle = viewModel::shuffleSongs,
                        actions = viewModel,
                        scrollLabel = songSort.scrollLabel(),
                        // Always there (empty without favorites), like the albums' header: if it only appeared with the first
                        // favorite, the list would keep its first row anchored and the chip would sit out of view above.
                        header = {
                            favorites?.let { chip -> ToggleChips(emptyList<Unit>(), { false }, { "" }, onToggle = {}, favorites = chip) }
                        },
                    )
                }
                LibraryTab.GENRES -> LoadableList(
                    genres,
                    // Music without genre tags: a dedicated hint; with no music at all, like the other tabs. While the songs
                    // are still loading, both are open (review D36).
                    empty = {
                        when {
                            songs == LoadState.Loading -> LoadingIndicator()
                            songs.valueOrNull.isNullOrEmpty() -> empty()
                            else -> EmptyHint(stringResource(R.string.genres_empty))
                        }
                    },
                ) { genreList ->
                    LoadableList(albums, empty) { all ->
                        val shown = albumsOfGenres.valueOrNull ?: all
                        AlbumGrid(
                            albums = shown,
                            onAlbumClick = onAlbumClick,
                            scrollLabel = albumSort.scrollLabel(),
                            pinHeader = true,
                            header = {
                                FilterHeader(
                                    chips = {
                                        ToggleChips(
                                            genreList,
                                            { genre -> genre.key in selectedGenres },
                                            { genre -> genre.name },
                                            onToggle = viewModel::toggleGenre,
                                            favorites = favoritesChip(FavoritesChip.GENRES, favoriteAlbumIds),
                                        )
                                    },
                                    filtered = selectedGenres.isNotEmpty() || FavoritesChip.GENRES in favoritesOnly,
                                    nothingShown = shown.isEmpty(),
                                    onPlay = { shuffle -> viewModel.playAlbums(shown, shuffle) },
                                )
                            },
                        )
                    }
                }
                LibraryTab.PLAYLISTS -> PlaylistList(
                    favoriteCount = favoriteCount,
                    favoriteAlbumCount = favoriteAlbumIds.size,
                    favoriteArtistCount = favoriteArtistIds.size,
                    // "New playlist" and "Favorites" are always there; the user's own playlists appear as soon as they are loaded.
                    playlists = playlists.valueOrNull.orEmpty(),
                    servers = servers,
                    onFavoritesClick = onFavoritesClick,
                    onPlaylistClick = onPlaylistClick,
                    onCreate = { name, serverSourceId -> viewModel.createPlaylist(name, serverSourceId, onPlaylistClick) },
                )
                LibraryTab.STATS -> MusicStatsScreen(onAlbumClick, onArtistClick, onGenreClick)
            }
        }
    }
}

@Composable
private fun PlaylistList(
    favoriteCount: Int,
    favoriteAlbumCount: Int,
    favoriteArtistCount: Int,
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
    ScrollToTopEffect(state)
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
                        supportingContent = {
                            // Songs always, albums and artists only if there are any.
                            val parts = listOfNotNull(
                                pluralStringResource(R.plurals.song_count, favoriteCount, favoriteCount),
                                favoriteAlbumCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.album_count, it, it) },
                                favoriteArtistCount.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.artist_count, it, it) },
                            )
                            Text(parts.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
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
                        var menu by remember { mutableStateOf(false) }
                        Box {
                            ListItem(
                                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = {
                                    val songs = pluralStringResource(R.plurals.song_count, playlist.songCount, playlist.songCount)
                                    Text("$songs · ${playlistLocation(playlist.serverName)}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                                },
                                leadingContent = { PlaylistIcon() },
                                // Long press opens the menu, as on albums and artists.
                                modifier = Modifier.combinedClickable(
                                    onClick = { onPlaylistClick(playlist.id) },
                                    onLongClickLabel = stringResource(R.string.action_more),
                                    onLongClick = { menu = true },
                                ),
                            )
                            if (menu) PlaylistRowMenu(playlist, onDismiss = { menu = false })
                        }
                    }
                }
            }
        }
        // The first two rows are "New playlist" and "Favorites".
        LazyListFastScroller(state, labelAt = { index -> foldedLetterOf(playlists.getOrNull((index - 2).coerceAtLeast(0))?.name.orEmpty()) })
    }
}

/**
 * Label of one of the six tabs (also of the podcast library). With less padding than `Tab(text = …)` (16 dp per side)
 * so "Playlists" fits on one line even on narrow phones; if it still doesn't fit, the font gets smaller instead of
 * wrapping.
 */
@Composable
internal fun TabLabel(text: String) {
    Box(Modifier.height(48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 11.sp, maxFontSize = MaterialTheme.typography.titleSmall.fontSize),
        )
    }
}

/** The "Favorites" chip in front of the other chips of a row. */
private class FavoritesChipState(val selected: Boolean, val onToggle: () -> Unit)

/**
 * A row of toggle chips (several at once), e.g. decades or genres above the albums; with [favorites]
 * the "Favorites" chip in front. If it doesn't fit the width, it can be swiped sideways.
 */
@Composable
private fun <T> ToggleChips(
    items: List<T>,
    selected: (T) -> Boolean,
    label: @Composable (T) -> String,
    onToggle: (T) -> Unit,
    favorites: FavoritesChipState? = null,
) {
    val chipScroll = rememberScrollState()
    Row(
        Modifier.keepHorizontalScroll(chipScroll).horizontalScroll(chipScroll).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        favorites?.let { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = chip.onToggle,
                label = { Text(stringResource(R.string.favorites_title)) },
                leadingIcon = {
                    Icon(
                        painterResource(if (chip.selected) DesignR.drawable.ic_favorite else DesignR.drawable.ic_favorite_border),
                        contentDescription = null,
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
            )
        }
        items.forEach { item ->
            FilterChip(selected = selected(item), onClick = { onToggle(item) }, label = { Text(label(item)) })
        }
    }
}

/**
 * Chip row above albums or artists; if something is selected ([filtered]), below it "Play" and "Shuffle" for the shown
 * entries, or a hint if the selection leaves nothing ([nothingShown]).
 */
@Composable
private fun FilterHeader(chips: @Composable () -> Unit, filtered: Boolean, nothingShown: Boolean, onPlay: (shuffle: Boolean) -> Unit) {
    Column {
        chips()
        when {
            nothingShown -> Text(
                stringResource(R.string.library_filter_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
            )
            filtered -> PlayShuffleRow(onPlay = { onPlay(false) }, onShuffle = { onPlay(true) }, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** "80s", from 2000 on "2000s" (decades before 1910 also with the full year). */
@Composable
private fun decadeLabel(startYear: Int): String =
    stringResource(R.string.decade, if (startYear in 1910..1999) startYear - 1900 else startYear)

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Fast scroller label for each album (e.g. the initial letter); `null`: thumb only. */
    scrollLabel: ((Album) -> String)? = null,
    /** A row above the albums (e.g. buttons or the sort), spanning the full width. */
    header: (@Composable () -> Unit)? = null,
    /** [header] (the library's chip bar) stays pinned above while scrolling, see [PinnableHeaderBox]. */
    pinHeader: Boolean = false,
) {
    val state = rememberLazyGridState()
    ScrollToTopEffect(state)
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    PinnableHeaderBox(header, pinHeader, modifier.fillMaxSize()) { inlineHeader ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 150.dp),
            state = state,
            contentPadding = gridContentPadding(pinnedHeader = header != null && inlineHeader == null),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (inlineHeader != null) fullWidthItem("header", inlineHeader)
            albumTiles(albums, onAlbumClick, playingAlbumId, playControl::playOrPauseAlbum)
        }
        val offset = if (inlineHeader != null) 1 else 0
        LazyGridFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> albums.getOrNull((index - offset).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}

private val AlbumSort.label: Int
    get() = when (this) {
        AlbumSort.TITLE -> R.string.sort_title
        AlbumSort.ARTIST -> R.string.sort_artist
        AlbumSort.YEAR -> R.string.sort_year
        AlbumSort.RECENTLY_ADDED -> R.string.sort_recently_added
        AlbumSort.RECENTLY_PLAYED -> R.string.sort_recently_played
    }

private val ArtistSort.label: Int
    get() = when (this) {
        ArtistSort.NAME -> R.string.sort_name
        ArtistSort.ALBUM_COUNT -> R.string.sort_album_count
    }

private val SongSort.label: Int
    get() = when (this) {
        SongSort.TITLE -> R.string.sort_title
        SongSort.ARTIST -> R.string.sort_artist
        SongSort.ALBUM -> R.string.sort_album
        SongSort.RECENTLY_ADDED -> R.string.sort_recently_added
    }

/** Fast scroller label matching the order; by date without a label (thumb only). */
private fun AlbumSort.scrollLabel(): ((Album) -> String)? = when (this) {
    AlbumSort.TITLE -> { album -> foldedLetterOf(album.title.sortKey()) }
    AlbumSort.ARTIST -> { album -> foldedLetterOf(album.artist.sortKey()) }
    AlbumSort.YEAR -> { album -> album.year?.toString() ?: "#" }
    AlbumSort.RECENTLY_ADDED, AlbumSort.RECENTLY_PLAYED -> null
}

private fun SongSort.scrollLabel(): ((Song) -> String)? = when (this) {
    SongSort.TITLE -> { song -> foldedLetterOf(song.title) }
    SongSort.ARTIST -> { song -> foldedLetterOf(song.artist.sortKey()) }
    SongSort.ALBUM -> { song -> foldedLetterOf(song.albumTitle.sortKey()) }
    SongSort.RECENTLY_ADDED -> null
}

/** Album tiles with a play button for a grid; [keyPrefix] separates several groups of the same albums in one grid. */
fun LazyGridScope.albumTiles(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    keyPrefix: String = "",
) {
    items(albums, key = { "$keyPrefix${it.id}" }) { album ->
        AlbumCard(
            album = album,
            onClick = { onAlbumClick(album.id) },
            modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer),
            onPlay = { onPlay(album.id) },
            playing = album.id == playingAlbumId,
            contentPadding = GridTilePadding,
        )
    }
}

/** An entry spanning the full width of a grid. */
fun LazyGridScope.fullWidthItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Artists as a grid, two per row, structured like the book authors: image, below it name and album count. */
@Composable
internal fun ArtistGrid(
    artists: List<Artist>,
    onArtistClick: (Long) -> Unit,
    /** Sorted by name: the fast scroller shows the initial letter. */
    byName: Boolean,
    /** A row above the artists (e.g. chips), spanning the full width. */
    header: (@Composable () -> Unit)? = null,
    /** [header] stays pinned above while scrolling, see [PinnableHeaderBox]. */
    pinHeader: Boolean = false,
) {
    val state = rememberLazyGridState()
    ScrollToTopEffect(state)
    PinnableHeaderBox(header, pinHeader, Modifier.fillMaxSize()) { inlineHeader ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            contentPadding = gridContentPadding(pinnedHeader = header != null && inlineHeader == null),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (inlineHeader != null) fullWidthItem("header", inlineHeader)
            items(artists, key = { it.id }) { artist ->
                var menu by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .gridTile(MaterialTheme.colorScheme.surfaceContainer)
                        .combinedClickable(
                            onClick = { onArtistClick(artist.id) },
                            onLongClickLabel = stringResource(R.string.action_more),
                            onLongClick = { menu = true },
                        )
                        .padding(GridTilePadding),
                ) {
                    if (menu) ArtistMenu(artist.id, artist.name, onDismiss = { menu = false })
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
        val offset = if (inlineHeader != null) 1 else 0
        LazyGridFastScroller(state, labelAt = if (byName) { index ->
            foldedLetterOf(artists.getOrNull((index - offset).coerceAtLeast(0))?.name.orEmpty().sortKey())
        } else null)
    }
}

/**
 * Songs with "Play" and "Shuffle" at the top like album, playlist and artist; also for favorites. [header] (chips) above the
 * buttons; if it leaves no songs, a hint instead of the buttons.
 */
@Composable
fun SongList(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
    /** Fast scroller label for each song (e.g. the initial letter); `null`: thumb only. */
    scrollLabel: ((Song) -> String)? = null,
    header: (@Composable () -> Unit)? = null,
) {
    val state = rememberLazyListState()
    ScrollToTopEffect(state)
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
            if (header != null) {
                item(key = "header") { Box(Modifier.padding(start = GroupMargin + 4.dp, end = GroupMargin, top = 8.dp)) { header() } }
            }
            item(key = "play") {
                if (songs.isEmpty()) {
                    Text(
                        stringResource(R.string.library_filter_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 16.dp),
                    )
                } else {
                    PlayShuffleRow(
                        onPlay = { onSongClick(0) },
                        onShuffle = onShuffle,
                        modifier = Modifier.padding(horizontal = GroupMargin + 12.dp, vertical = 4.dp),
                    )
                }
            }
            groupedItemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongListItem(song = song, onClick = { onSongClick(index) }, actions = actions)
            }
        }
        // Before the songs: the buttons and possibly the header.
        val offset = if (header != null) 2 else 1
        LazyListFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> songs.getOrNull((index - offset).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}
