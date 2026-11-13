package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.spoken.BookQueue
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.asLoadState
import de.kleinabersmart.earloft.ui.components.QueueMoreMenu
import de.kleinabersmart.earloft.ui.components.QueueTitle
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.orOnError
import de.kleinabersmart.earloft.ui.valueOrNull
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Die Warteschlange der Bücher („Weiterhören“) bearbeiten: wie bei Musik und Podcasts mit Ziehgriff, ✕ und ⋮. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookQueueScreen(
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: BookQueueViewModel = hiltViewModel(),
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val bookSpeed by viewModel.bookSpeed.collectAsStateWithLifecycle()
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                QueueTitle(
                    summary?.let { pluralStringResource(R.plurals.book_queue_summary, it.count, it.count, formatDuration(it.remainingMs)) },
                )
            },
            navigationIcon = { BackButton(onBack) },
            actions = {
                QueueMoreMenu(
                    clearMessage = stringResource(R.string.book_queue_clear_message),
                    // Das geladene Buch bleibt; zu leeren gibt es nur etwas, wenn noch ein anderes dasteht.
                    canClear = books.valueOrNull.orEmpty().any { it.book.id != currentId },
                    onClear = viewModel::clear,
                )
            },
        )
        SpokenQueueList(
            entries = books,
            currentId = currentId,
            emptyText = stringResource(R.string.book_queue_empty),
            inheritedSpeed = { bookSpeed },
            onClick = onBookClick,
            onRemove = viewModel::remove,
            onReorder = viewModel::reorder,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@HiltViewModel
class BookQueueViewModel @Inject constructor(
    private val queue: BookQueue,
    playback: PlaybackConnection,
    spokenSettings: SpokenSettings,
) : ViewModel() {

    /** Vorgabe der Bücher für die Restzeit von Büchern ohne eigenes Tempo. */
    val bookSpeed: StateFlow<Float> = spokenSettings.bookEffects.map { it.speed }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 1f)

    val books: StateFlow<LoadState<List<BookWithProgress>>> = queue.books
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Das gerade geladene Buch. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Anzahl der Bücher und ihre Restzeit zusammen, jedes bei seinem Tempo (Kopf der Warteschlange); `null` = leer. */
    val summary: StateFlow<QueueSummary?> = combine(queue.books, spokenSettings.bookEffects) { books, area ->
        if (books.isEmpty()) null else QueueSummary(books.size, books.sumOf { it.remainingPlayMs(area.speed) ?: 0L })
    }.orOnError(null).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** „Warteschlange leeren“; das geladene Buch bleibt. */
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
