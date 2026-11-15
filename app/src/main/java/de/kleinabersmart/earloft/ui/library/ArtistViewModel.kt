package de.kleinabersmart.earloft.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.repository.FavoritesRepository
import de.kleinabersmart.earloft.core.data.repository.LibraryRepository
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.ArtistDetails
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadStateOrMissing
import de.kleinabersmart.earloft.ui.components.SongActions
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel(assistedFactory = ArtistViewModel.Factory::class)
class ArtistViewModel @AssistedInject constructor(
    @Assisted private val artistId: Long,
    private val libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
    private val favoritesRepository: FavoritesRepository,
) : ViewModel(), SongActions {

    val artist: StateFlow<LoadState<ArtistDetails>> = libraryRepository.artist(artistId)
        .asLoadStateOrMissing()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LoadState.Loading)

    /** Die meistgehörten Songs; leer, solange keiner gehört wurde. */
    val topSongs: StateFlow<List<Song>> = libraryRepository.topSongsOfArtist(artistId, TOP_SONGS)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val appearsOn: StateFlow<List<Album>> = libraryRepository.appearsOn(artistId)
        .orOnError(emptyList())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Alle Songs des Künstlers, nach Jahr oder gemischt. */
    fun play(shuffle: Boolean) {
        viewModelScope.launch {
            val songs = libraryRepository.songsOfArtist(artistId)
            if (songs.isNotEmpty()) playbackConnection.play(songs, shuffle = shuffle)
        }
    }

    fun playTopSongs(startIndex: Int) = playbackConnection.play(topSongs.value, startIndex)

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    override fun toggleFavorite(song: Song) {
        viewModelScope.launch { favoritesRepository.setFavorite(song.id, !song.isFavorite) }
    }

    @AssistedFactory
    interface Factory {
        fun create(artistId: Long): ArtistViewModel
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
        const val TOP_SONGS = 5
    }
}
