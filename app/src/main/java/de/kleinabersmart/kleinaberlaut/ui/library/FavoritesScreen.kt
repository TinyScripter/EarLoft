package de.kleinabersmart.kleinaberlaut.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.model.WearSelectionKind
import de.kleinabersmart.kleinaberlaut.ui.components.LoadableList
import de.kleinabersmart.kleinaberlaut.ui.components.WatchStatusText
import de.kleinabersmart.kleinaberlaut.ui.components.WatchToggleButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: FavoritesViewModel = hiltViewModel(),
) {
    val songs by viewModel.songs.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text(stringResource(R.string.favorites_title))
                    WatchStatusText(WearSelectionKind.FAVORITES, refId = null)
                }
            },
            navigationIcon = { BackButton(onBack) },
            actions = { WatchToggleButton(WearSelectionKind.FAVORITES, refId = null) },
        )
        LoadableList(songs, stringResource(R.string.favorites_empty)) {
            SongList(songs = it, onSongClick = viewModel::play, onShuffle = viewModel::shuffle, actions = viewModel)
        }
    }
}
