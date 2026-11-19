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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.components.QueueMoreMenu
import de.kleinabersmart.earloft.ui.components.QueueTitle
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.valueOrNull

/** Edit the books queue ("Continue listening"): as with music and podcasts, with drag handle, ✕ and ⋮. */
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
                    // The loaded book stays; there is only something to clear if another one is still there.
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
