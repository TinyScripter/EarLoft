package de.kleinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.kleinaberlaut.core.data.repository.FavoritesRepository
import de.kleinabersmart.kleinaberlaut.core.model.Song
import de.kleinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import de.kleinabersmart.kleinaberlaut.ui.LoadState
import de.kleinabersmart.kleinaberlaut.ui.asLoadState
import de.kleinabersmart.kleinaberlaut.ui.components.SongActions
import de.kleinabersmart.kleinaberlaut.ui.valueOrNull
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
