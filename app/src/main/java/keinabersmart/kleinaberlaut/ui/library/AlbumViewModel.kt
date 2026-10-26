package keinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.model.AlbumDetails
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.toLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

@HiltViewModel(assistedFactory = AlbumViewModel.Factory::class)
class AlbumViewModel @AssistedInject constructor(
    @Assisted albumId: Long,
    libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel(), SongActions {

    val album: StateFlow<LoadState<AlbumDetails>> = libraryRepository.album(albumId)
        .map { it.toLoadState() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    fun play(startIndex: Int = 0) {
        songs()?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffle() {
        songs()?.let { playbackConnection.play(it, shuffle = true) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    private fun songs(): List<Song>? = (album.value as? LoadState.Ready)?.value?.songs

    @AssistedFactory
    interface Factory {
        fun create(albumId: Long): AlbumViewModel
    }
}
