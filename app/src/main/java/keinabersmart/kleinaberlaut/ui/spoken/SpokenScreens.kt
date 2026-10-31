package keinabersmart.kleinaberlaut.ui.spoken

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.pluralStringResource
import keinabersmart.kleinaberlaut.core.common.sortKey
import keinabersmart.kleinaberlaut.core.model.parseSeries
import keinabersmart.kleinaberlaut.navigation.BookGroupKind
import keinabersmart.kleinaberlaut.ui.components.LazyGridFastScroller
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.components.LazyListFastScroller
import keinabersmart.kleinaberlaut.ui.components.letterOf
import kotlinx.coroutines.launch
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SpokenLibraryViewModel @Inject constructor(
    books: BookRepository,
    servers: AudiobookshelfServers,
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
}

/** Bücher mit gleichem Autor bzw. aus der gleichen Serie. */
data class BookGroup(val name: String, val books: List<BookWithProgress>)

/** Nach Autor; Bücher ohne Autor entfallen. Gruppen nach Name, Bücher darin nach Titel. */
fun List<BookWithProgress>.authorGroups(): List<BookGroup> =
    filter { it.book.author.isNotBlank() }
        .groupBy { it.book.author.trim() }
        .map { (name, books) -> BookGroup(name, books.sortedBy { it.book.title.sortKey() }) }
        .sortedBy { it.name.sortKey() }

/** Nach Serie (ohne Bandnummer, ein Buch kann in mehreren stehen); Bücher darin nach Band, dann Titel. */
fun List<BookWithProgress>.seriesGroups(): List<BookGroup> =
    flatMap { item -> parseSeries(item.book.series).map { Triple(it.name, it.sequence, item) } }
        .groupBy { it.first }
        .map { (name, entries) ->
            val sorted = entries.sortedWith(compareBy({ it.second ?: Double.MAX_VALUE }, { it.third.book.title.sortKey() }))
            BookGroup(name, sorted.map { it.third })
        }
        .sortedBy { it.name.sortKey() }

fun List<BookWithProgress>.groups(kind: BookGroupKind): List<BookGroup> =
    if (kind == BookGroupKind.SERIES) seriesGroups() else authorGroups()

/** Start im Modus „Bücher“: weiterhören und neu hinzugekommene Bücher. */
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

private enum class BookTab(@StringRes val label: Int) {
    ALL(R.string.spoken_tab_all),
    SERIES(R.string.spoken_tab_series),
    AUTHORS(R.string.spoken_tab_authors),
}

/** Bibliothek im Modus „Bücher“: Alle (Raster), Serien und Autoren (Listen) wie bei der Musik. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenLibraryScreen(
    onBookClick: (Long) -> Unit,
    onGroupClick: (BookGroupKind, String) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.allBooks.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { BookTab.entries.size }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            books.isNullOrEmpty() && hasServer == true -> EmptyHint(stringResource(R.string.spoken_no_books))
            else -> {
                val all = books.orEmpty()
                val series = remember(all) { all.seriesGroups() }
                val authors = remember(all) { all.authorGroups() }
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    BookTab.entries.forEachIndexed { index, tab ->
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            text = { Text(stringResource(tab.label)) },
                        )
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                    when (BookTab.entries[page]) {
                        BookTab.ALL -> BookGrid(all, onBookClick, fastScroll = true)
                        BookTab.SERIES -> BookGroupList(series) { onGroupClick(BookGroupKind.SERIES, it) }
                        BookTab.AUTHORS -> BookGroupList(authors) { onGroupClick(BookGroupKind.AUTHOR, it) }
                    }
                }
            }
        }
    }
}

/** Bücher als Raster; mit Schnellscroller, wenn die Liste nach Titel sortiert ist. */
@Composable
private fun BookGrid(books: List<BookWithProgress>, onBookClick: (Long) -> Unit, fastScroll: Boolean = false) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(books, key = { it.book.id }) { item ->
                BookCard(item, onClick = { onBookClick(item.book.id) })
            }
        }
        if (fastScroll) {
            LazyGridFastScroller(state, labelAt = { index -> letterOf(books.getOrNull(index)?.book?.title.orEmpty().sortKey()) })
        }
    }
}

/** Serien bzw. Autoren mit dem Cover des ersten Buchs und der Anzahl. */
@Composable
private fun BookGroupList(groups: List<BookGroup>, onClick: (String) -> Unit) {
    if (groups.isEmpty()) {
        EmptyHint(stringResource(R.string.library_empty))
        return
    }
    val state = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), state = state) {
            items(groups, key = { it.name }) { group ->
                ListItem(
                    headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(pluralStringResource(R.plurals.book_count, group.books.size, group.books.size)) },
                    leadingContent = { BookArt(group.books.first().book.id, Modifier.size(48.dp), MaterialTheme.shapes.small) },
                    modifier = Modifier.clickable { onClick(group.name) },
                )
            }
        }
        LazyListFastScroller(state, labelAt = { index -> letterOf(groups.getOrNull(index)?.name.orEmpty().sortKey()) })
    }
}

/** Alle Bücher einer Serie oder eines Autors. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookGroupScreen(
    kind: BookGroupKind,
    name: String,
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val books by viewModel.allBooks.collectAsStateWithLifecycle()
    val group = remember(books, kind, name) {
        books.orEmpty().groups(kind).firstOrNull { it.name == name }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        BookGrid(group?.books.orEmpty(), onBookClick)
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
