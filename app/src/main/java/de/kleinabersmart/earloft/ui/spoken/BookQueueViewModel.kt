package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.core.data.spoken.BookQueue
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.orOnError
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BookQueueViewModel @Inject constructor(
    private val queue: BookQueue,
    playback: PlaybackConnection,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    /** Books default for the remaining time of books without their own speed. */
    val bookSpeed: StateFlow<Float> = spokenSettings.bookEffects.map { it.speed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val books: StateFlow<LoadState<List<BookWithProgress>>> = queue.books
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** The currently loaded book. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Number of books and their combined remaining time, each at its own speed (queue header); `null` = empty. */
    val summary: StateFlow<QueueSummary?> = combine(queue.books, spokenSettings.bookEffects) { books, area ->
        if (books.isEmpty()) null else QueueSummary(books.size, books.sumOf { it.remainingPlayMs(area.speed) ?: 0L })
    }.orOnError(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** "Clear queue"; the loaded book stays. */
    fun clear() {
        viewModelScope.launch { queue.clear(keepId = currentBookId.value) }
    }

    fun remove(bookId: Long) {
        viewModelScope.launch { queue.remove(bookId) }
    }

    fun reorder(bookIds: List<Long>, movedId: Long) {
        viewModelScope.launch { queue.reorder(bookIds, movedId) }
    }
}
