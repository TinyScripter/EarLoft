package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumGridSize
import de.kleinabersmart.earloft.core.model.AlbumSort
import de.kleinabersmart.earloft.core.model.AlbumView
import de.kleinabersmart.earloft.core.model.ColorFamily
import de.kleinabersmart.earloft.core.model.colorFamilyOf
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.ui.components.AlbumListItem
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.LocalOfflineController
import de.kleinabersmart.earloft.ui.components.OfflineBadge
import de.kleinabersmart.earloft.ui.components.PinnableHeaderBox
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import de.kleinabersmart.earloft.ui.components.foldedLetterOf
import de.kleinabersmart.earloft.ui.components.gridContentPadding
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.player.LocalMiniPlayerScrollHide
import de.kleinabersmart.earloft.ui.spoken.LocalAppHeaderCollapsed
import de.kleinabersmart.earloft.ui.spoken.ShowStartOnNewFront
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbarHeight
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Below this height the record crate and the carousel show the list instead: chips, cover and details wouldn't fit. */
private val SINGLE_ALBUM_MIN_HEIGHT = 400.dp

/** Back from a scrolled list only with this much more: about the collapsed app header and the hidden mini player. */
private val LIST_HYSTERESIS = 160.dp

/**
 * Whether the crate or carousel shows the list instead, with [height] available and the list shown so far ([asList]). The height a
 * [snackbar] takes away doesn't count: a passing message switched to the list for good on a small phone (review4 Y27).
 *
 * Scrolling the list collapses the app header and hides the mini player, which gives it room the crate wouldn't have; while that
 * lasts ([scrolledAway]) it needs [LIST_HYSTERESIS] more to go back, or the two kept swapping mid-scroll (review4 T56). A list at
 * rest has the crate's height, so there the plain bound decides: the crate comes back once a mini player that pushed it to the list
 * goes again.
 */
internal fun singleAlbumAsList(height: Dp, snackbar: Dp, asList: Boolean, scrolledAway: Boolean): Boolean =
    height + snackbar < SINGLE_ALBUM_MIN_HEIGHT + if (asList && scrolledAway) LIST_HYSTERESIS else 0.dp

/** Whether the crate or carousel currently shows the list instead (see [LIST_HYSTERESIS]); read and written while composing. */
private class SingleAlbumLayout(var asList: Boolean = false)

/** A pinch beyond this factor changes the tile size by one step. */
private const val PINCH_STEP = 1.25f

/**
 * Albums of the library in the view chosen in the header ([AlbumViewButton]): tiles (three sizes), rows, shelves, the record
 * crate ([AlbumCrate]) or the carousel ([AlbumCarousel]). Used wherever the library shows a grid of albums (tabs Albums and
 * Genres, genre page, favorites). [sort] is the order of [albums]: it gives the fast scroller its label and the shelves their
 * groups; `null` (an order of its own, e.g. favorites) shows the grid instead of shelves.
 */
@Composable
fun AlbumBrowser(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    sort: AlbumSort? = null,
    /** A row above the albums (e.g. buttons or the chips), spanning the full width. */
    header: (@Composable () -> Unit)? = null,
    /** [header] (the library's chip bar) stays pinned above while scrolling, see [PinnableHeaderBox]. */
    pinHeader: Boolean = false,
    /** Sort and filter: if they change, the albums show their start ([ShowStartOnNewFront]). */
    resetKey: Any? = null,
) {
    val browse: AlbumBrowseViewModel = hiltViewModel()
    val view by browse.view.collectAsStateWithLifecycle()
    val gridSize by browse.gridSize.collectAsStateWithLifecycle()
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    val scrollLabel = albumScrollLabel(sort)
    val list: @Composable () -> Unit = {
        AlbumRowList(albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum, modifier)
    }
    val grid: @Composable () -> Unit = {
        AlbumTileGrid(
            albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum,
            size = gridSize ?: AlbumGridSize.MEDIUM,
            onResize = browse::setGridSize,
            modifier = modifier,
        )
    }
    when (view) {
        // Read from the settings in a moment; until then nothing, so no other view flashes up.
        null -> Box(modifier.fillMaxSize())
        AlbumView.GRID -> if (gridSize != null) grid() else Box(modifier.fillMaxSize())
        AlbumView.LIST -> list()
        AlbumView.SHELVES -> if (sort == null) {
            grid()
        } else {
            AlbumShelves(
                albums, sort, onAlbumClick, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum,
                onPlayShelf = playControl::playAlbums,
                modifier = modifier,
            )
        }
        // Too low for one album at a time (phone in landscape): the list, which scrolls and lets the app header collapse.
        AlbumView.CRATE, AlbumView.CAROUSEL -> BoxWithConstraints(modifier.fillMaxSize()) {
            // With hysteresis: the list lets the app header collapse and the mini player hide, which makes room. Without it the
            // crate came back mid-scroll, where the header doesn't expand again, and the two kept swapping (review4 T56).
            val layout = remember { SingleAlbumLayout() }
            val snackbar by LocalSnackbarHeight.current
            val headerCollapsed by LocalAppHeaderCollapsed.current
            val scrolledAway = headerCollapsed || LocalMiniPlayerScrollHide.current.hidden
            layout.asList = singleAlbumAsList(maxHeight, snackbar, layout.asList, scrolledAway)
            when {
                layout.asList -> AlbumRowList(
                    albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum,
                )
                view == AlbumView.CRATE -> AlbumCrate(
                    albums = albums,
                    onAlbumClick = onAlbumClick,
                    label = scrollLabel,
                    header = header,
                    resetKey = resetKey,
                    playingAlbumId = playingAlbumId,
                    onPlay = playControl::playOrPauseAlbum,
                    seedColor = browse::seedColor,
                )
                else -> AlbumCarousel(
                    albums = albums,
                    onAlbumClick = onAlbumClick,
                    label = scrollLabel,
                    header = header,
                    resetKey = resetKey,
                    playingAlbumId = playingAlbumId,
                    onPlay = playControl::playOrPauseAlbum,
                )
            }
        }
    }
}

/** Fast scroller label matching the order: initial letter, year or color; by date without a label (thumb only). */
@Composable
private fun albumScrollLabel(sort: AlbumSort?): ((Album) -> String)? {
    val colorNames = ColorFamily.entries.associateWith { colorFamilyName(it) }
    return when (sort) {
        AlbumSort.TITLE -> { album -> foldedLetterOf(album.title.sortKey()) }
        AlbumSort.ARTIST -> { album -> foldedLetterOf(album.artist.sortKey()) }
        AlbumSort.YEAR -> { album -> album.year?.toString() ?: "#" }
        AlbumSort.COLOR -> { album -> colorNames.getValue(colorFamilyOf(album.seedColor)) }
        AlbumSort.RECENTLY_ADDED, AlbumSort.RECENTLY_PLAYED, null -> null
    }
}

/**
 * Header button for the album view (left of the sort); its icon shows the chosen view. Below the views, with the grid chosen,
 * the tile size (the way without the pinch gesture, e.g. for TalkBack).
 */
@Composable
fun AlbumViewButton() {
    val browse: AlbumBrowseViewModel = hiltViewModel()
    val view by browse.view.collectAsStateWithLifecycle()
    val gridSize by browse.gridSize.collectAsStateWithLifecycle()
    val selected = view ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    val title = stringResource(R.string.album_view_label)
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(selected.icon), contentDescription = "$title: ${stringResource(selected.label)}")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            val check: @Composable (Boolean) -> Unit = { on ->
                if (on) Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null)
            }
            AlbumView.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(stringResource(option.label)) },
                    leadingIcon = { Icon(painterResource(option.icon), contentDescription = null) },
                    trailingIcon = { check(option == selected) },
                    onClick = {
                        open = false
                        browse.setView(option)
                    },
                )
            }
            if (selected == AlbumView.GRID) {
                HorizontalDivider()
                AlbumGridSize.entries.forEach { size ->
                    DropdownMenuItem(
                        text = { Text(stringResource(size.label)) },
                        trailingIcon = { check(size == gridSize) },
                        onClick = {
                            open = false
                            browse.setGridSize(size)
                        },
                    )
                }
            }
        }
    }
}

private val AlbumView.label: Int
    get() = when (this) {
        AlbumView.GRID -> R.string.album_view_grid
        AlbumView.LIST -> R.string.album_view_list
        AlbumView.SHELVES -> R.string.album_view_shelves
        AlbumView.CRATE -> R.string.album_view_crate
        AlbumView.CAROUSEL -> R.string.album_view_carousel
    }

private val AlbumView.icon: Int
    get() = when (this) {
        AlbumView.GRID -> DesignR.drawable.ic_grid_view
        AlbumView.LIST -> DesignR.drawable.ic_list
        AlbumView.SHELVES -> DesignR.drawable.ic_shelves
        AlbumView.CRATE -> DesignR.drawable.ic_album
        AlbumView.CAROUSEL -> DesignR.drawable.ic_view_carousel
    }

private val AlbumGridSize.label: Int
    get() = when (this) {
        AlbumGridSize.SMALL -> R.string.album_grid_small
        AlbumGridSize.MEDIUM -> R.string.album_grid_medium
        AlbumGridSize.LARGE -> R.string.album_grid_large
    }

/**
 * Albums as tiles, the [AlbumView.GRID] of the [AlbumBrowser], in three [size]s: [AlbumGridSize.SMALL] a cover wall without
 * text, [AlbumGridSize.LARGE] one tile per row on a phone. Spreading two fingers makes the tiles larger, pinching smaller
 * ([onResize], one step per gesture); one finger scrolls as usual.
 */
@Composable
private fun AlbumTileGrid(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    scrollLabel: ((Album) -> String)?,
    header: (@Composable () -> Unit)?,
    pinHeader: Boolean,
    resetKey: Any?,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    size: AlbumGridSize,
    onResize: (AlbumGridSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyGridState()
    ScrollToTopEffect(state)
    // An album found by a sync ("Recently added") or just played ("Recently played") appears at the top in view.
    ShowStartOnNewFront(albums, state, { it.id }, resetKey = resetKey)
    val currentSize by rememberUpdatedState(size)
    val spacing = if (size == AlbumGridSize.SMALL) 4.dp else 8.dp
    PinnableHeaderBox(
        header,
        pinHeader,
        modifier
            .fillMaxSize()
            .pinchSteps { step ->
                val sizes = AlbumGridSize.entries
                onResize(sizes[(currentSize.ordinal + step).coerceIn(0, sizes.lastIndex)])
            },
    ) { inlineHeader ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(
                minSize = when (size) {
                    AlbumGridSize.SMALL -> 88.dp
                    AlbumGridSize.MEDIUM -> 150.dp
                    AlbumGridSize.LARGE -> 280.dp
                },
            ),
            state = state,
            contentPadding = gridContentPadding(pinnedHeader = header != null && inlineHeader == null),
            horizontalArrangement = Arrangement.spacedBy(spacing),
            verticalArrangement = Arrangement.spacedBy(spacing),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (inlineHeader != null) fullWidthItem("header", inlineHeader)
            if (size == AlbumGridSize.SMALL) {
                items(albums, key = { it.id }) { album -> AlbumCoverTile(album, onClick = { onAlbumClick(album.id) }) }
            } else {
                albumTiles(albums, onAlbumClick, playingAlbumId, onPlay)
            }
        }
        val offset = if (inlineHeader != null) 1 else 0
        LazyGridFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> albums.getOrNull((index - offset).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}

/** Only the cover (with the download mark), for the cover wall; TalkBack reads title and artist, long press opens the menu. */
@Composable
private fun AlbumCoverTile(album: Album, onClick: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { menu = true },
            )
            .semantics { contentDescription = "${album.title}, ${album.artist}" },
    ) {
        if (menu) AlbumMenu(album, onDismiss = { menu = false })
        AlbumArt(albumId = album.id, modifier = Modifier.fillMaxWidth(), shape = shape)
        OfflineBadge(LocalOfflineController.current?.albumMarkOf(album.id), Modifier.align(Alignment.BottomStart).padding(4.dp))
    }
}

/**
 * Reports a two-finger gesture once it has spread ([PINCH_STEP]) or pinched enough: +1 larger, -1 smaller, once per gesture.
 * Reads the pointers before the list (initial pass) and takes them only while two fingers are down, so scrolling stays.
 */
internal fun Modifier.pinchSteps(onStep: (Int) -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var zoom = 1f
        var stepped = false
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.count { it.pressed } >= 2) {
                zoom *= event.calculateZoom()
                event.changes.forEach { it.consume() }
                if (!stepped && (zoom > PINCH_STEP || zoom < 1f / PINCH_STEP)) {
                    stepped = true
                    onStep(if (zoom > 1f) 1 else -1)
                }
            }
        } while (event.changes.any { it.pressed })
    }
}

/** Albums as grouped rows with play button and ⋮, the [AlbumView.LIST] of the [AlbumBrowser]. */
@Composable
private fun AlbumRowList(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    scrollLabel: ((Album) -> String)?,
    header: (@Composable () -> Unit)?,
    pinHeader: Boolean,
    resetKey: Any?,
    playingAlbumId: Long?,
    onPlay: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = rememberLazyListState()
    ScrollToTopEffect(state)
    ShowStartOnNewFront(albums, state, { it.id }, resetKey = resetKey)
    // The chips start where the rows' text does, as above the songs.
    PinnableHeaderBox(
        header,
        pinHeader,
        modifier.fillMaxSize(),
        headerPadding = PaddingValues(start = GroupMargin + 4.dp, end = GroupMargin, top = 8.dp),
    ) { inlineHeader ->
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp)) {
            if (inlineHeader != null) {
                item(key = "header") { Box(Modifier.padding(start = GroupMargin + 4.dp, end = GroupMargin, bottom = 8.dp)) { inlineHeader() } }
            }
            groupedItems(albums, key = { it.id }) { album ->
                AlbumListItem(
                    album = album,
                    onClick = { onAlbumClick(album.id) },
                    onPlay = { onPlay(album.id) },
                    playing = album.id == playingAlbumId,
                    withYear = true,
                )
            }
        }
        val offset = if (inlineHeader != null) 1 else 0
        LazyListFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> albums.getOrNull((index - offset).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}
