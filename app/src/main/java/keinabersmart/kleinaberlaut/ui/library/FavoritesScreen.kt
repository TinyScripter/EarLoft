package keinabersmart.kleinaberlaut.ui.library

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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
import keinabersmart.kleinaberlaut.ui.components.LoadableList
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.WatchStatusText
import keinabersmart.kleinaberlaut.ui.components.WatchToggleButton
import keinabersmart.kleinaberlaut.ui.valueOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel(), SongActions {

    val songs: StateFlow<LoadState<List<Song>>> = favoritesRepository.favorites()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun play(startIndex: Int) {
        songs.value.valueOrNull?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffle() {
        songs.value.valueOrNull?.let { playbackConnection.play(it, shuffle = true) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }
}

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
