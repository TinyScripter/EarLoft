package de.kleinabersmart.kleinaberlaut.ui.spoken

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
import de.kleinabersmart.kleinaberlaut.core.common.sortKey
import de.kleinabersmart.kleinaberlaut.core.model.parseSeries
import de.kleinabersmart.kleinaberlaut.navigation.BookGroupKind
import de.kleinabersmart.kleinaberlaut.ui.components.LazyGridFastScroller
import de.kleinabersmart.kleinaberlaut.ui.library.BackButton
import de.kleinabersmart.kleinaberlaut.ui.components.LazyListFastScroller
import de.kleinabersmart.kleinaberlaut.ui.components.letterOf
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
import androidx.compose.ui.Alignment
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.model.BookWithProgress
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.SideEffect
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import de.kleinabersmart.kleinaberlaut.core.model.AppUris
import androidx.compose.runtime.LaunchedEffect
import de.kleinabersmart.kleinaberlaut.ui.LoadState
import de.kleinabersmart.kleinaberlaut.ui.components.EmptyHint
import de.kleinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import de.kleinabersmart.kleinaberlaut.ui.components.LoadingIndicator
import de.kleinabersmart.kleinaberlaut.ui.valueOrNull
import de.kleinabersmart.kleinaberlaut.ui.components.PlayPauseButton
import de.kleinabersmart.kleinaberlaut.ui.components.GridTilePadding
import de.kleinabersmart.kleinaberlaut.ui.components.Shelf
import de.kleinabersmart.kleinaberlaut.ui.components.gridTile
import de.kleinabersmart.kleinaberlaut.ui.components.groupedItems
import de.kleinabersmart.kleinaberlaut.ui.components.ShelfInset
import de.kleinabersmart.kleinaberlaut.ui.components.keepHorizontalScroll

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
    val continueState by viewModel.continueListening.collectAsStateWithLifecycle()
    val recentState by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val continueListening = continueState.valueOrNull.orEmpty()
    val recent = recentState.valueOrNull.orEmpty()
    val rowState = rememberContinueRowState(continueListening.map { it.book.id })

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            else -> LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                if (continueListening.isNotEmpty()) {
                    item(key = "continue") {
                        Shelf(stringResource(R.string.spoken_continue)) {
                            BookRow(continueListening, onBookClick, state = rowState, playingId = playingId, onPlay = viewModel::playOrPause)
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") {
                        Shelf(stringResource(R.string.spoken_recent)) {
                            BookRow(recent, onBookClick, playingId = playingId, onPlay = viewModel::playOrPause)
                        }
                    }
                }
                if (continueState == LoadState.Failed || recentState == LoadState.Failed) {
                    item(key = "failed") { LoadFailedHint() }
                } else if (continueState is LoadState.Ready && recentState is LoadState.Ready &&
                    continueListening.isEmpty() && recent.isEmpty() && hasServer == true
                ) {
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
    STATS(R.string.spoken_tab_stats),
}

/** Bibliothek im Modus „Bücher“: Alle (Raster), Serien und Autoren (Listen) wie bei der Musik, dazu die Statistik. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenLibraryScreen(
    onBookClick: (Long) -> Unit,
    onGroupClick: (BookGroupKind, String) -> Unit,
    onSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val booksState by viewModel.allBooks.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { BookTab.entries.size }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            booksState == LoadState.Failed -> LoadFailedHint()
            booksState == LoadState.Loading || hasServer == null -> LoadingIndicator()
            booksState.valueOrNull.isNullOrEmpty() -> EmptyHint(stringResource(R.string.spoken_no_books))
            else -> {
                val all = booksState.valueOrNull.orEmpty()
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
                        BookTab.ALL -> BookGrid(all, onBookClick, playingId, viewModel::playOrPause, fastScroll = true)
                        BookTab.SERIES -> BookGroupList(series) { onGroupClick(BookGroupKind.SERIES, it) }
                        BookTab.AUTHORS -> AuthorGrid(authors) { onGroupClick(BookGroupKind.AUTHOR, it) }
                        BookTab.STATS -> BookStatsScreen(onBookClick)
                    }
                }
            }
        }
    }
}

/** Bücher als Raster; mit Schnellscroller, wenn die Liste nach Titel sortiert ist. */
@Composable
private fun BookGrid(
    books: List<BookWithProgress>,
    onBookClick: (Long) -> Unit,
    playingId: Long?,
    onPlay: (Long) -> Unit,
    fastScroll: Boolean = false,
) {
    val state = rememberLazyGridState()
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(books, key = { it.book.id }) { item ->
                BookCard(
                    item,
                    onClick = { onBookClick(item.book.id) },
                    modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer),
                    onPlay = { onPlay(item.book.id) },
                    playing = item.book.id == playingId,
                    contentPadding = GridTilePadding,
                )
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
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(authors, key = { it.name }) { author ->
                Column(
                    Modifier
                        .gridTile(MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onClick(author.name) }
                        .padding(GridTilePadding),
                ) {
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
                        minLines = 2,
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
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
            groupedItems(groups, key = { it.name }) { group ->
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
    val booksState by viewModel.allBooks.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val group = remember(booksState, kind, name) {
        booksState.valueOrNull?.groups(kind)?.firstOrNull { it.name == name }
    }
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        when (booksState) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing, LoadState.Failed -> LoadFailedHint()
            is LoadState.Ready -> if (group == null) {
                EmptyHint(stringResource(R.string.item_missing))
            } else {
                BookGrid(group.books, onBookClick, playingId, viewModel::playOrPause)
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
internal fun BookRow(
    books: List<BookWithProgress>,
    onBookClick: (Long) -> Unit,
    state: LazyListState = rememberLazyListState(),
    playingId: Long? = null,
    onPlay: ((Long) -> Unit)? = null,
) {
    LazyRow(
        state = state,
        modifier = Modifier.keepHorizontalScroll(state),
        contentPadding = PaddingValues(horizontal = ShelfInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(books, key = { it.book.id }) { item ->
            BookCard(
                item,
                onClick = { onBookClick(item.book.id) },
                modifier = Modifier.width(140.dp).rowItemAnimation(this),
                onPlay = onPlay?.let { play -> { play(item.book.id) } },
                playing = item.book.id == playingId,
            )
        }
    }
}

/**
 * Zustand der Zeile „Weiterhören“ ([ids] in ihrer Reihenfolge): Kommt ein Eintrag von weiter hinten nach vorn, zeigt die
 * Zeile wieder ihren Anfang. Fällt nur der vorderste weg (zu Ende gehört, aus der Warteschlange genommen), rückt der
 * Rest gleitend nach ([rowItemAnimation]); ein Sprung an den Anfang schnitte diese Animation ab.
 */
@Composable
fun rememberContinueRowState(ids: List<Long>): LazyListState {
    val state = rememberLazyListState()
    val previous = remember { arrayOf(emptyList<Long>()) }
    val before = previous[0]
    val first = ids.firstOrNull()
    LaunchedEffect(first) {
        if (before.getOrNull(1) != first) state.scrollToItem(0)
    }
    SideEffect { previous[0] = ids }
    return state
}

/**
 * Kachel einer horizontalen Zeile: Ein entfernter Eintrag löst sich schnell auf, die Kacheln rechts davon gleiten
 * danach nach links, als schiebe sich die Zeile zusammen.
 */
fun Modifier.rowItemAnimation(scope: LazyItemScope): Modifier = with(scope) {
    this@rowItemAnimation.animateItem(
        fadeInSpec = tween(ROW_FADE_MS),
        placementSpec = tween(ROW_SLIDE_MS, delayMillis = ROW_FADE_MS / 2, easing = FastOutSlowInEasing),
        fadeOutSpec = tween(ROW_FADE_MS),
    )
}

private const val ROW_FADE_MS = 150
private const val ROW_SLIDE_MS = 350

/**
 * Cover mit Fortschrittsbalken, darunter Titel und Autor; mit [onPlay] sitzt ein Play- (bei [playing] Pause-)Knopf unten rechts
 * auf dem Cover, oben links die Download-Markierung ([BookDownloadBadge]).
 */
@Composable
fun BookCard(
    item: BookWithProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
    contentPadding: Dp = 0.dp,
) {
    // Kontextmenü per Langklick, bei Folgen und Büchern.
    var menu by remember { mutableStateOf(false) }
    val episode = item.isEpisode
    Column(modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }).padding(contentPadding)) {
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false })
        Box {
            BookArt(item.book.id, Modifier.fillMaxWidth())
            BookDownloadBadge(item.book.id, Modifier.align(Alignment.TopStart).padding(6.dp))
            if (onPlay != null) {
                PlayPauseButton(
                    playing,
                    onPlay,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    playDescription = if (episode) R.string.podcast_play_episode else R.string.spoken_play,
                )
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
