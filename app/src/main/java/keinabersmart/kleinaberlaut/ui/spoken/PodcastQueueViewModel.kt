package keinabersmart.kleinaberlaut.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PodcastQueueViewModel @Inject constructor(
    private val continueListening: ContinueListening,
    private val launcher: BookLauncher,
    playback: PlaybackConnection,
) : ViewModel() {

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = continueListening.episodes
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Die gerade geladene Folge. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(bookId: Long) = launcher.play(bookId)

    fun remove(bookId: Long) {
        viewModelScope.launch { continueListening.remove(bookId) }
    }

    fun reorder(bookIds: List<Long>) {
        viewModelScope.launch { continueListening.reorder(bookIds) }
    }
}
