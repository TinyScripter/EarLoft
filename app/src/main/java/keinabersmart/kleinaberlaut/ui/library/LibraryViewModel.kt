package keinabersmart.kleinaberlaut.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.repository.LibraryRepository
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.Song
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.components.SongActions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    libraryRepository: LibraryRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel(), SongActions {

    /** `null`, solange noch geladen wird. */
    val albums: StateFlow<List<Album>?> = libraryRepository.albums().stateInViewModel()
    val artists: StateFlow<List<Artist>?> = libraryRepository.artists().stateInViewModel()
    val songs: StateFlow<List<Song>?> = libraryRepository.songs().stateInViewModel()

    fun playSongs(startIndex: Int) {
        songs.value?.let { playbackConnection.play(it, startIndex) }
    }

    fun shuffleSongs() {
        songs.value?.let { playbackConnection.play(it, shuffle = true) }
    }

    override fun playNext(song: Song) = playbackConnection.playNext(listOf(song))

    override fun addToQueue(song: Song) = playbackConnection.addToQueue(listOf(song))

    private fun <T> Flow<List<T>>.stateInViewModel(): StateFlow<List<T>?> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
