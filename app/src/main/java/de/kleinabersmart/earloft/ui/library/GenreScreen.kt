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
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/** Die Alben eines Genres, darüber „Abspielen“ und „Zufällig“ für alle seine Songs. */
@Composable
fun GenreScreen(
    genre: String,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: GenreViewModel = hiltViewModel<GenreViewModel, GenreViewModel.Factory> { it.create(genre) },
) {
    val state by viewModel.albums.collectAsStateWithLifecycle()
    AlbumCollectionScreen(
        title = genre,
        state = state,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onPlay = viewModel::play,
        modifier = modifier,
    )
}

/** Alben einer Auswahl (Genre, Jahrzehnt) unter [title], darüber „Abspielen“ und „Zufällig“ ([onPlay] mit `shuffle`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AlbumCollectionScreen(
    title: String,
    state: LoadState<List<Album>>,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlay: (shuffle: Boolean) -> Unit,
    modifier: Modifier = Modifier,
    scrollLabel: ((Album) -> String)? = null,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        when (state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
            is LoadState.Ready -> if (state.value.isEmpty()) {
                EmptyHint(stringResource(R.string.item_missing))
            } else {
                AlbumGrid(
                    albums = state.value,
                    onAlbumClick = onAlbumClick,
                    scrollLabel = scrollLabel,
                    header = { PlayShuffleRow(onPlay = { onPlay(false) }, onShuffle = { onPlay(true) }) },
                )
            }
        }
    }
}

/** „Abspielen“ und „Zufällig“ nebeneinander, wie auf der Albumseite. */
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
