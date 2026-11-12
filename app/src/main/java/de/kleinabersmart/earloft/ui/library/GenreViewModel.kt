package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = GenreViewModel.Factory::class)
class GenreViewModel @AssistedInject constructor(
    @Assisted private val genre: String,
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    val albums: StateFlow<LoadState<List<Album>>> = libraryRepository.albumsOfGenre(genre)
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Alle Songs des Genres, Album für Album oder gemischt. */
    fun play(shuffle: Boolean) {
        viewModelScope.launch {
            val songs = libraryRepository.songsOfGenre(genre)
            if (songs.isNotEmpty()) playbackConnection.play(songs, shuffle = shuffle)
        }
    }

    @AssistedFactory
    interface Factory {
        fun create(genre: String): GenreViewModel
    }
}
