package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.WatchStatusText
import de.kleinabersmart.earloft.ui.components.WatchToggleButton
import kotlinx.coroutines.launch

/** In the order of the library's tabs. */
private enum class FavoritesTab(val label: Int) {
    ALBUMS(R.string.library_albums),
    ARTISTS(R.string.library_artists),
    SONGS(R.string.library_songs),
}

/**
 * Favorites in three tabs (as in the library: albums, artists, songs), most recently favorited first. "Onto the watch" applies to the songs and the entire favorited albums
 * (Wear concept 5) and is therefore shown for both, not for the artists.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val artists by viewModel.artists.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { FavoritesTab.entries.size }
    val scope = rememberCoroutineScope()
    val onWatchTab = FavoritesTab.entries[pagerState.targetPage] != FavoritesTab.ARTISTS
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.favorites_title))
                    if (onWatchTab) WatchStatusText(WearSelectionKind.FAVORITES, refId = null)
                }
            },
            navigationIcon = { BackButton(onBack) },
            actions = { if (onWatchTab) WatchToggleButton(WearSelectionKind.FAVORITES, refId = null) },
        )
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            FavoritesTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                ) { TabLabel(stringResource(tab.label)) }
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
            when (FavoritesTab.entries[page]) {
                FavoritesTab.SONGS -> LoadableList(songs, stringResource(R.string.favorites_empty)) {
                    SongList(songs = it, onSongClick = viewModel::play, onShuffle = viewModel::shuffle, actions = viewModel)
                }
                FavoritesTab.ALBUMS -> LoadableList(albums, stringResource(R.string.favorites_albums_empty)) {
                    AlbumGrid(albums = it, onAlbumClick = onAlbumClick)
                }
                FavoritesTab.ARTISTS -> LoadableList(artists, stringResource(R.string.favorites_artists_empty)) {
                    ArtistGrid(it, onArtistClick, byName = false)
                }
            }
        }
    }
}
