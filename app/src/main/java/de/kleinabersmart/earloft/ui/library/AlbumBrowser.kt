package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumView
import de.kleinabersmart.earloft.ui.components.AlbumListItem
import de.kleinabersmart.earloft.ui.components.ChoiceButton
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.PinnableHeaderBox
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.spoken.ShowStartOnNewFront
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Albums of the library in the view chosen in the header ([AlbumViewButton]): tiles, rows or the record crate ([AlbumCrate]).
 * Used wherever the library shows a grid of albums (tabs Albums and Genres, genre page, favorites).
 */
@Composable
fun AlbumBrowser(
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Fast scroller label for each album (e.g. the initial letter); `null`: thumb only. */
    scrollLabel: ((Album) -> String)? = null,
    /** A row above the albums (e.g. buttons or the chips), spanning the full width. */
    header: (@Composable () -> Unit)? = null,
    /** [header] (the library's chip bar) stays pinned above while scrolling, see [PinnableHeaderBox]. */
    pinHeader: Boolean = false,
    /** Sort and filter: if they change, the albums show their start ([ShowStartOnNewFront]). */
    resetKey: Any? = null,
) {
    val browse: AlbumBrowseViewModel = hiltViewModel()
    val view by browse.view.collectAsStateWithLifecycle()
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    when (view) {
        // Read from the settings in a moment; until then nothing, so no other view flashes up.
        null -> Box(modifier.fillMaxSize())
        AlbumView.GRID -> AlbumTileGrid(
            albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum, modifier,
        )
        AlbumView.LIST -> AlbumRowList(
            albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum, modifier,
        )
        // Too low for the crate (phone in landscape): the list, which scrolls and lets the app header collapse.
        AlbumView.CRATE -> BoxWithConstraints(modifier.fillMaxSize()) {
            if (maxHeight < CRATE_MIN_HEIGHT) {
                AlbumRowList(
                    albums, onAlbumClick, scrollLabel, header, pinHeader, resetKey, playingAlbumId, playControl::playOrPauseAlbum,
                )
            } else {
                AlbumCrate(
                    albums = albums,
                    onAlbumClick = onAlbumClick,
                    label = scrollLabel,
                    header = header,
                    resetKey = resetKey,
                    playingAlbumId = playingAlbumId,
                    onPlay = playControl::playOrPauseAlbum,
                    seedColor = browse::seedColor,
                )
            }
        }
    }
}

/** Below this height the record crate shows the list instead: chips, sleeve and details wouldn't fit. */
private val CRATE_MIN_HEIGHT = 400.dp

/** Header button for the album view (left of the sort); its icon shows the chosen view. */
@Composable
fun AlbumViewButton() {
    val browse: AlbumBrowseViewModel = hiltViewModel()
    val view by browse.view.collectAsStateWithLifecycle()
    val selected = view ?: return
    ChoiceButton(
        selected = selected,
        options = AlbumView.entries,
        label = { stringResource(it.label) },
        icon = selected.icon,
        title = stringResource(R.string.album_view_label),
        onSelect = browse::setView,
    )
}

private val AlbumView.label: Int
    get() = when (this) {
        AlbumView.GRID -> R.string.album_view_grid
        AlbumView.LIST -> R.string.album_view_list
        AlbumView.CRATE -> R.string.album_view_crate
    }

private val AlbumView.icon: Int
    get() = when (this) {
        AlbumView.GRID -> DesignR.drawable.ic_grid_view
        AlbumView.LIST -> DesignR.drawable.ic_list
        AlbumView.CRATE -> DesignR.drawable.ic_album
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
