package de.kleinabersmart.tinyamp.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.tinyamp.core.data.spoken.BookRepository
import de.kleinabersmart.tinyamp.core.data.podcast.EpisodeQueue
import de.kleinabersmart.tinyamp.core.model.BookWithProgress
import de.kleinabersmart.tinyamp.core.playback.PlaybackConnection
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.asLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SpokenLibraryViewModel @Inject constructor(
    books: BookRepository,
    servers: AudiobookshelfServers,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    queue: EpisodeQueue,
) : ViewModel() {

    /** Das Buch, das gerade läuft (nicht: pausiert). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Läuft das Buch, pausiert es; ist es geladen und pausiert, geht es weiter; sonst startet oder setzt es fort
     * (und steht danach ganz vorn in „Weiterhören“).
     */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }

    val continueListening: StateFlow<LoadState<List<BookWithProgress>>> = books.continueListening(startOrder = queue.order)
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val recentlyAdded: StateFlow<LoadState<List<BookWithProgress>>> = books.recentlyAdded()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    val allBooks: StateFlow<LoadState<List<BookWithProgress>>> = books.books()
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** `null`, solange noch geladen wird. */
    val hasServer: StateFlow<Boolean?> = servers.servers.map<_, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
