package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.data.spoken.SpokenRefresher
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel
class SpokenLibraryViewModel @Inject constructor(
    books: BookRepository,
    servers: AudiobookshelfServers,
    private val refresher: SpokenRefresher,
) : ViewModel() {

    val continueListening: StateFlow<List<BookWithProgress>> = books.continueListening()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentlyAdded: StateFlow<List<BookWithProgress>> = books.recentlyAdded()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `null`, solange noch geladen wird. */
    val allBooks: StateFlow<List<BookWithProgress>?> = books.books()
        .map<List<BookWithProgress>, List<BookWithProgress>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** `null`, solange noch geladen wird. */
    val hasServer: StateFlow<Boolean?> = servers.servers.map<_, Boolean?> { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun refresh() = refresher.refreshAll()
}

/** Start im Modus „Gesprochenes“: weiterhören und neu hinzugekommene Bücher. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenHomeScreen(
    onBookClick: (Long) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val continueListening by viewModel.continueListening.collectAsStateWithLifecycle()
    val recent by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.spoken_title_home)) },
            actions = {
                IconButton(onClick = viewModel::refresh) {
                    Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.spoken_abs_sync))
                }
            },
        )
        when {
            hasServer == false -> NoServerHint(onSetup)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                if (continueListening.isNotEmpty()) {
                    item(key = "continue-header") { SectionHeader(stringResource(R.string.spoken_continue)) }
                    item(key = "continue") { BookRow(continueListening, onBookClick) }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent-header") { SectionHeader(stringResource(R.string.spoken_recent)) }
                    item(key = "recent") { BookRow(recent, onBookClick) }
                }
                if (continueListening.isEmpty() && recent.isEmpty() && hasServer == true) {
                    item(key = "empty") { EmptyHint(stringResource(R.string.spoken_no_books)) }
                }
            }
        }
    }
}

/** Bibliothek im Modus „Gesprochenes“: alle Bücher als Raster. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenLibraryScreen(
    onBookClick: (Long) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.allBooks.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()

    Column(modifier.fillMaxSize()) {
        TopAppBar(title = { Text(stringResource(R.string.spoken_all_books)) })
        when {
            hasServer == false -> NoServerHint(onSetup)
            books.isNullOrEmpty() && hasServer == true -> EmptyHint(stringResource(R.string.spoken_no_books))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(150.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(books.orEmpty(), key = { it.book.id }) { item ->
                    BookCard(item, onClick = { onBookClick(item.book.id) })
                }
            }
        }
    }
}

@Composable
private fun NoServerHint(onSetup: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp)) {
        Text(stringResource(R.string.spoken_no_server), style = MaterialTheme.typography.bodyLarge)
        FilledTonalButton(onClick = onSetup, modifier = Modifier.padding(top = 12.dp)) {
            Text(stringResource(R.string.spoken_setup))
        }
    }
}

@Composable
private fun BookRow(books: List<BookWithProgress>, onBookClick: (Long) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.book.id }) { item ->
            BookCard(item, onClick = { onBookClick(item.book.id) }, modifier = Modifier.width(140.dp))
        }
    }
}

/** Cover mit Fortschrittsbalken, darunter Titel und Autor. */
@Composable
fun BookCard(item: BookWithProgress, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        BookArt(item.book.id, Modifier.fillMaxWidth())
        val progress = item.progress
        if (progress != null && progress.positionMs > 0 && !progress.finished) {
            LinearProgressIndicator(
                progress = { progress.fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                drawStopIndicator = {},
            )
        }
        Text(
            item.book.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            item.book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
