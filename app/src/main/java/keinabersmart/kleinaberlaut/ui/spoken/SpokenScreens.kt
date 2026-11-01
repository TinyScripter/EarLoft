package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.layout.height
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR
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
import keinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import keinabersmart.kleinaberlaut.core.model.AppUris
import androidx.compose.runtime.LaunchedEffect
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

    val continueListening: StateFlow<List<BookWithProgress>> = books.continueListening(startOrder = queue.order)
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
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val rowState = rememberLazyListState()
    // Springt ein anderes Buch an die erste Stelle, steht es wieder ganz links sichtbar.
    LaunchedEffect(continueListening.firstOrNull()?.book?.id) { rowState.scrollToItem(0) }

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                if (continueListening.isNotEmpty()) {
                    item(key = "continue-header") { SectionHeader(stringResource(R.string.spoken_continue)) }
                    item(key = "continue") {
                        BookRow(continueListening, onBookClick, state = rowState, playingId = playingId, onPlay = viewModel::playOrPause)
                    }
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
                        BookTab.AUTHORS -> AuthorGrid(authors) { onGroupClick(BookGroupKind.AUTHOR, it) }
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

/**
 * Autoren als Raster, zwei pro Reihe: Autorenbild (von Audiobookshelf, sonst aus dem Internet, wenn die Option an ist), darunter
 * Name und Anzahl der Bücher. Ohne Bild bleibt das Cover des ersten Buchs.
 */
@Composable
private fun AuthorGrid(authors: List<BookGroup>, onClick: (String) -> Unit) {
    if (authors.isEmpty()) {
        EmptyHint(stringResource(R.string.library_empty))
        return
    }
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = state,
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(authors, key = { it.name }) { author ->
                Column(Modifier.clickable { onClick(author.name) }) {
                    Box {
                        BookArt(author.books.first().book.id, Modifier.fillMaxWidth())
                        // Liegt über dem Buchcover; schlägt das Laden fehl, bleibt das Cover sichtbar.
                        AsyncImage(
                            model = AppUris.authorImage(author.name),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alignment = Alignment.TopCenter,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(8.dp)),
                        )
                    }
                    Text(
                        author.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(
                        pluralStringResource(R.plurals.book_count, author.books.size, author.books.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        LazyGridFastScroller(state, labelAt = { index -> letterOf(authors.getOrNull(index)?.name.orEmpty().sortKey()) })
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
private fun BookRow(
    books: List<BookWithProgress>,
    onBookClick: (Long) -> Unit,
    state: LazyListState = rememberLazyListState(),
    playingId: Long? = null,
    onPlay: ((Long) -> Unit)? = null,
) {
    LazyRow(
        state = state,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.book.id }) { item ->
            BookCard(
                item,
                onClick = { onBookClick(item.book.id) },
                modifier = Modifier.width(140.dp),
                onPlay = onPlay?.let { play -> { play(item.book.id) } },
                playing = item.book.id == playingId,
            )
        }
    }
}

/** Cover mit Fortschrittsbalken, darunter Titel und Autor; mit [onPlay] sitzt ein Play- (bei [playing] Pause-)Knopf unten rechts auf dem Cover. */
@Composable
fun BookCard(item: BookWithProgress, onClick: () -> Unit, modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null, playing: Boolean = false) {
    // Podcast-Folgen haben ein Kontextmenü (Langklick), Hörbücher nicht.
    var menu by remember { mutableStateOf(false) }
    val episode = item.isEpisode
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = if (episode) ({ menu = true }) else null)) {
        if (episode) EpisodeMenu(item, expanded = menu, onDismiss = { menu = false })
        Box {
            BookArt(item.book.id, Modifier.fillMaxWidth())
            if (!episode) BookDownloadBadge(item.book.id, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (onPlay != null) {
                FilledTonalIconButton(
                    onClick = onPlay,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(36.dp),
                ) {
                    if (playing) {
                        Icon(painterResource(DesignR.drawable.ic_pause), contentDescription = stringResource(R.string.action_pause))
                    } else {
                        Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.podcast_play_episode))
                    }
                }
            }
        }
        // Alle Kacheln einer Zeile sind gleich hoch, egal ob begonnen und wie lang der Titel ist: sonst springt der Inhalt
        // darunter, wenn beim Scrollen andere Kacheln sichtbar werden. Deshalb Platz für den Balken und zwei Titelzeilen.
        val progress = item.progress
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(PROGRESS_HEIGHT)) {
            if (progress != null && progress.positionMs > 0 && !progress.finished) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            }
        }
        Text(
            item.book.title,
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            item.book.author,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Erscheinungsdatum der Folge (bei Büchern ist `addedAt` nur der Zeitpunkt des Abgleichs); die Zeile bleibt als Platz stehen.
        if (episode) {
            Text(
                item.book.addedAt.takeIf { it > 0 }?.let(::formatEpisodeDate).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
            )
        }
    }
}

private val PROGRESS_HEIGHT = 4.dp
