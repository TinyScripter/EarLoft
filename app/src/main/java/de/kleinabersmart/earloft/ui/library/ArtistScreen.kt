package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.ShareButton
import de.kleinabersmart.earloft.core.data.share.ShareTarget

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

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                (state as? LoadState.Ready)?.let {
                    Text(it.value.artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            navigationIcon = { BackButton(onBack) },
            actions = { if (state is LoadState.Ready) ShareButton(ShareTarget.Artist(artistId)) },
        )
        when (val current = state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
            is LoadState.Ready -> AlbumGrid(albums = current.value.albums, onAlbumClick = onAlbumClick)
        }
    }
}
