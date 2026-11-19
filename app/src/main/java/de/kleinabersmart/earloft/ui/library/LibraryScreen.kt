package de.kleinabersmart.earloft.ui.library

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
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.letterOf
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
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val decades by viewModel.decades.collectAsStateWithLifecycle()
    val albumSort by viewModel.albumSort.collectAsStateWithLifecycle()
    val artistSort by viewModel.artistSort.collectAsStateWithLifecycle()
    val songSort by viewModel.songSort.collectAsStateWithLifecycle()
    val favoriteCount by viewModel.favoriteCount.collectAsStateWithLifecycle()
    // Der Chip „Favoriten“ steht vorn, sobald es etwas zu zeigen gibt, und bleibt, solange er an ist.
    val favoritesChip: (FavoritesChip, Set<Long>) -> FavoritesChipState? = { chip, ids ->
        val on = chip in favoritesOnly
        if (ids.isEmpty() && !on) null else FavoritesChipState(on) { viewModel.toggleFavoritesOnly(chip) }
    }
    val playlists by viewModel.playlists.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()
    // Tabs und Seiten teilen sich den Pager-Zustand: Antippen und Wischen führen zum selben Ergebnis.
    val pagerState = rememberPagerState { LibraryTab.entries.size }
    val scope = rememberCoroutineScope()
    // Ohne Musik: an erster Stelle einen Server einrichten, darunter der Weg zum Ausblenden des Bereichs.
    val empty: @Composable () -> Unit = {
        EmptyAreaHint(SpokenMode.MUSIC, stringResource(R.string.library_empty), stringResource(R.string.home_setup_server), onAddServer)
    }

    // Die Sortierung des gezeigten Reiters steht im Kopf der App links von der Lupe; die Genres zeigen Alben, also deren Sortierung.
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
                // Leer heißt: gar keine Alben. Lässt die Auswahl nichts übrig, bleiben die Chips stehen, damit man sie lösen kann.
                // Die Kopfzeile ist immer da (notfalls leer): Käme sie erst mit dem ersten Favoriten hinzu, hielte das Raster
                // die erste Kachel fest und schöbe die Chips oben aus dem Bild.
                LibraryTab.ALBUMS -> LoadableList(albums, empty) { all ->
                    val shown = albumsOfDecades.valueOrNull ?: all
                    val favorites = favoritesChip(FavoritesChip.ALBUMS, favoriteAlbumIds)
                    AlbumGrid(
                        albums = shown,
                        onAlbumClick = onAlbumClick,
                        scrollLabel = albumSort.scrollLabel(),
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
                LibraryTab.SONGS -> LoadableList(songs, empty) {
                    SongList(
                        songs = it,
                        onSongClick = viewModel::playSongs,
                        onShuffle = viewModel::shuffleSongs,
                        actions = viewModel,
                        scrollLabel = songSort.scrollLabel(),
                    )
                }
                LibraryTab.GENRES -> LoadableList(
                    genres,
                    // Musik ohne Genre-Angaben: eigener Hinweis; ganz ohne Musik wie die anderen Reiter. Solange die Songs
                    // noch laden, ist beides offen (Review D36).
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
                    // „Neue Playlist“ und „Favoriten“ stehen immer da; die eigenen Playlists erscheinen, sobald sie geladen sind.
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
                            // Songs immer, Alben und Künstler nur, wenn es welche gibt.
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

/**
 * Beschriftung eines der sechs Reiter (auch der Podcast-Bibliothek). Mit weniger Rand als `Tab(text = …)` (16 dp je Seite),
 * damit „Playlists“ auch auf schmalen Handys in eine Zeile passt; reicht es trotzdem nicht, wird die Schrift kleiner statt
 * umzubrechen.
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

/** Der Chip „Favoriten“ vor den übrigen Chips einer Reihe. */
private class FavoritesChipState(val selected: Boolean, val onToggle: () -> Unit)

/**
 * Eine Reihe von Chips zum An- und Abschalten (mehrere zugleich), z. B. Jahrzehnte oder Genres über den Alben; mit [favorites]
 * vorn der Chip „Favoriten“. Passt sie nicht in die Breite, lässt sie sich seitlich wischen.
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
 * Chip-Reihe über Alben bzw. Künstlern; ist etwas ausgewählt ([filtered]), darunter „Abspielen“ und „Zufällig“ für die gezeigten
 * Einträge, oder ein Hinweis, wenn die Auswahl nichts übrig lässt ([nothingShown]).
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

/** „80er“, ab 2000 „2000er“ (Jahrzehnte vor 1910 ebenfalls mit voller Jahreszahl). */
@Composable
private fun decadeLabel(startYear: Int): String =
    stringResource(R.string.decade, if (startYear in 1910..1999) startYear - 1900 else startYear)

@Composable
fun AlbumGrid(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Marke des Schnellscrollers zu jedem Album (z. B. der Anfangsbuchstabe); `null`: nur der Griff. */
    scrollLabel: ((Album) -> String)? = null,
    /** Eine Zeile über den Alben (z. B. Knöpfe oder die Sortierung), über die ganze Breite. */
    header: (@Composable () -> Unit)? = null,
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
            if (header != null) fullWidthItem("header", header)
            albumTiles(albums, onAlbumClick, playingAlbumId, playControl::playOrPauseAlbum)
        }
        val offset = if (header != null) 1 else 0
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

/** Marke des Schnellscrollers passend zur Reihenfolge; nach Datum ohne Marke (nur der Griff). */
private fun AlbumSort.scrollLabel(): ((Album) -> String)? = when (this) {
    AlbumSort.TITLE -> { album -> letterOf(album.title.sortKey()) }
    AlbumSort.ARTIST -> { album -> letterOf(album.artist.sortKey()) }
    AlbumSort.YEAR -> { album -> album.year?.toString() ?: "#" }
    AlbumSort.RECENTLY_ADDED, AlbumSort.RECENTLY_PLAYED -> null
}

private fun SongSort.scrollLabel(): ((Song) -> String)? = when (this) {
    SongSort.TITLE -> { song -> letterOf(song.title) }
    SongSort.ARTIST -> { song -> letterOf(song.artist.sortKey()) }
    SongSort.ALBUM -> { song -> letterOf(song.albumTitle.sortKey()) }
    SongSort.RECENTLY_ADDED -> null
}

/** Album-Kacheln mit Play-Knopf für ein Raster; [keyPrefix] trennt mehrere Gruppen derselben Alben in einem Raster. */
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

/** Ein Eintrag über die ganze Breite eines Rasters. */
fun LazyGridScope.fullWidthItem(key: Any, content: @Composable () -> Unit) {
    item(key = key, span = { GridItemSpan(maxLineSpan) }) { content() }
}

/** Künstler als Raster, zwei pro Reihe, aufgebaut wie die Autoren der Bücher: Bild, darunter Name und Zahl der Alben. */
@Composable
internal fun ArtistGrid(
    artists: List<Artist>,
    onArtistClick: (Long) -> Unit,
    /** Nach Name sortiert: der Schnellscroller zeigt den Anfangsbuchstaben. */
    byName: Boolean,
    /** Eine Zeile über den Künstlern (z. B. Chips), über die ganze Breite. */
    header: (@Composable () -> Unit)? = null,
) {
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
            if (header != null) fullWidthItem("header", header)
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
        val offset = if (header != null) 1 else 0
        LazyGridFastScroller(state, labelAt = if (byName) { index ->
            letterOf(artists.getOrNull((index - offset).coerceAtLeast(0))?.name.orEmpty().sortKey())
        } else null)
    }
}

/** Songs mit „Zufällig“-Knopf oben; auch für Favoriten und Playlists. */
@Composable
fun SongList(
    songs: List<Song>,
    onSongClick: (Int) -> Unit,
    onShuffle: () -> Unit,
    actions: SongActions,
    /** Marke des Schnellscrollers zu jedem Song (z. B. der Anfangsbuchstabe); `null`: nur der Griff. */
    scrollLabel: ((Song) -> String)? = null,
) {
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 16.dp)) {
            item(key = "shuffle") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FilledTonalButton(onClick = onShuffle) {
                        Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null)
                        Text(stringResource(R.string.action_shuffle), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            groupedItemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
                SongListItem(song = song, onClick = { onSongClick(index) }, actions = actions)
            }
        }
        // Index 0 ist der „Zufällig“-Knopf.
        LazyListFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> songs.getOrNull((index - 1).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}
