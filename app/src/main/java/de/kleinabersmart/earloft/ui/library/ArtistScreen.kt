package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.ArtistDetails
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.GroupedItem
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.PlayControlViewModel
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.ShareButton
import de.kleinabersmart.earloft.ui.components.SongListItem
import de.kleinabersmart.earloft.ui.components.CollectionFavoriteButton
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistScreen(
    artistId: Long,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ArtistViewModel = hiltViewModel<ArtistViewModel, ArtistViewModel.Factory> { it.create(artistId) },
) {
    val state by viewModel.artist.collectAsStateWithLifecycle()
    val topSongs by viewModel.topSongs.collectAsStateWithLifecycle()
    val appearsOn by viewModel.appearsOn.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                (state as? LoadState.Ready)?.let {
                    Text(it.value.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { BackButton(onBack) },
            actions = {
                (state as? LoadState.Ready)?.let { ready ->
                    CollectionFavoriteButton(FavoriteKind.ARTIST, artistId)
                    ShareButton(ShareTarget.Artist(artistId))
                    var menu by rememberSaveable { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) {
                            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                        }
                        if (menu) ArtistMenu(artistId, ready.value.artist.name, onDismiss = { menu = false })
                    }
                }
            },
        )
        when (val current = state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
            is LoadState.Ready -> ArtistContent(
                details = current.value,
                topSongs = topSongs,
                appearsOn = appearsOn,
                onAlbumClick = onAlbumClick,
                onPlay = { viewModel.play(shuffle = false) },
                onShuffle = { viewModel.play(shuffle = true) },
                onTopSongClick = viewModel::playTopSongs,
                actions = viewModel,
            )
        }
    }
}

/**
 * Abspielen/Zufällig über alle Songs des Künstlers, darunter (sofern gehört) seine meistgehörten Songs, seine Alben und
 * die Alben anderer, auf denen er mitwirkt. Überschriften nur, wenn es mehr als die eigenen Alben gibt.
 */
@Composable
private fun ArtistContent(
    details: ArtistDetails,
    topSongs: List<Song>,
    appearsOn: List<Album>,
    onAlbumClick: (Long) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onTopSongClick: (Int) -> Unit,
    actions: ArtistViewModel,
) {
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    val headers = topSongs.isNotEmpty() || appearsOn.isNotEmpty()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 150.dp),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        fullWidthItem("actions") { PlayShuffleRow(onPlay = onPlay, onShuffle = onShuffle) }
        if (topSongs.isNotEmpty()) {
            fullWidthItem("top-header") { SectionHeader(stringResource(R.string.artist_most_played)) }
            // Ein Eintrag für alle: Der Abstand zwischen den Rasterzeilen würde die Gruppe sonst zerteilen.
            fullWidthItem("top-songs") {
                Column {
                    topSongs.forEachIndexed { index, song ->
                        key(song.id) {
                            GroupedItem(index, topSongs.size) {
                                SongListItem(song = song, onClick = { onTopSongClick(index) }, actions = actions)
                            }
                        }
                    }
                }
            }
        }
        if (headers && details.albums.isNotEmpty()) {
            fullWidthItem("albums-header") { SectionHeader(stringResource(R.string.artist_albums)) }
        }
        albumTiles(details.albums, onAlbumClick, playingAlbumId, playControl::playOrPauseAlbum)
        if (appearsOn.isNotEmpty()) {
            fullWidthItem("appears-header") { SectionHeader(stringResource(R.string.artist_appears_on)) }
            albumTiles(appearsOn, onAlbumClick, playingAlbumId, playControl::playOrPauseAlbum, keyPrefix = "appears-")
        }
    }
}
