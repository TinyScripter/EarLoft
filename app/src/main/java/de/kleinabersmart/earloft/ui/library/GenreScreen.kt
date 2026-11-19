package de.kleinabersmart.earloft.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** The albums of a genre, above them "Play" and "Shuffle" for all its songs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreScreen(
    genre: String,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenreViewModel = hiltViewModel<GenreViewModel, GenreViewModel.Factory> { it.create(genre) },
) {
    val state by viewModel.albums.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(genre, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        when (val current = state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
            is LoadState.Ready -> if (current.value.isEmpty()) {
                EmptyHint(stringResource(R.string.item_missing))
            } else {
                AlbumGrid(
                    albums = current.value,
                    onAlbumClick = onAlbumClick,
                    header = { PlayShuffleRow(onPlay = { viewModel.play(shuffle = false) }, onShuffle = { viewModel.play(shuffle = true) }) },
                )
            }
        }
    }
}

/** "Play" and "Shuffle" side by side, as on the album page. */
@Composable
internal fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Button(onClick = onPlay) {
            Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_play))
        }
        FilledTonalButton(onClick = onShuffle) {
            Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_shuffle))
        }
    }
}
