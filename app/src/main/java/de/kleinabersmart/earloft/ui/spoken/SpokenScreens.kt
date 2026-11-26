package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.library.TabLabel
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.lazy.grid.LazyGridItemScope
import androidx.compose.material3.TextButton
import de.kleinabersmart.earloft.core.data.spoken.SeriesSection
import de.kleinabersmart.earloft.core.data.spoken.seriesSections
import kotlinx.coroutines.flow.flowOf
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookSort
import de.kleinabersmart.earloft.core.model.AuthorSort
import de.kleinabersmart.earloft.core.model.SeriesSort
import de.kleinabersmart.earloft.core.model.authorLine
import de.kleinabersmart.earloft.core.model.bookAuthors
import de.kleinabersmart.earloft.core.model.parseSeries
import de.kleinabersmart.earloft.ui.components.HeaderAction
import de.kleinabersmart.earloft.ui.components.SortButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.core.data.spoken.BookGroup
import de.kleinabersmart.earloft.core.data.spoken.authorGroups
import de.kleinabersmart.earloft.core.data.spoken.SeriesProgress
import de.kleinabersmart.earloft.core.data.spoken.SeriesStep
import de.kleinabersmart.earloft.core.data.spoken.genreGroups
import de.kleinabersmart.earloft.core.data.spoken.sameGenre
import de.kleinabersmart.earloft.core.data.spoken.seriesProgress
import de.kleinabersmart.earloft.core.data.spoken.narratorGroups
import de.kleinabersmart.earloft.core.data.spoken.seriesGroups
import de.kleinabersmart.earloft.navigation.BookGroupKind
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.ui.components.PinnableHeaderBox
import de.kleinabersmart.earloft.ui.components.gridContentPadding
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.foldedLetterOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.lazy.grid.LazyGridState
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.ui.snackbar.SnackbarMessage
import de.kleinabersmart.earloft.ui.snackbar.Snackbars
import androidx.compose.runtime.DisposableEffect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import androidx.compose.foundation.gestures.animateScrollBy
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import de.kleinabersmart.earloft.core.model.BookWithProgress
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
import de.kleinabersmart.earloft.core.model.AppUris
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyAreaHint
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.core.model.SpokenServer
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.ui.components.PlayPauseButton
import de.kleinabersmart.earloft.ui.components.GridTilePadding
import de.kleinabersmart.earloft.ui.components.Shelf
import de.kleinabersmart.earloft.ui.components.gridTile
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.ShelfInset
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll

fun List<BookWithProgress>.groups(kind: BookGroupKind): List<BookGroup> = when (kind) {
    BookGroupKind.SERIES -> seriesGroups()
    BookGroupKind.AUTHOR -> authorGroups()
    BookGroupKind.NARRATOR -> narratorGroups()
    BookGroupKind.GENRE -> genreGroups()
}

/** Start in "Books" mode: continue listening and newly added books. */
@Composable
fun SpokenHomeScreen(
    onBookClick: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onSetup: () -> Unit,
    onEditServer: (String) -> Unit,
    onOpenArdSounds: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val ardSounds by viewModel.ardSounds.collectAsStateWithLifecycle()
    val continueState by viewModel.continueListening.collectAsStateWithLifecycle()
    val recentState by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val continueSeries by viewModel.continueSeries.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val signInNeeded by viewModel.signInNeeded.collectAsStateWithLifecycle()
    val hasBooks by viewModel.hasBooks.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val continueListening = continueState.valueOrNull.orEmpty()
    val recent = recentState.valueOrNull.orEmpty()
    val continueRow = rememberFrontRow(continueListening) { it.book.id }
    val recentRow = rememberFrontRow(recent) { it.book.id }
    val seriesRow = rememberFrontRow(continueSeries) { it.first.book.id }

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            else -> {
                val listState = rememberLazyListState()
                ScrollToTopEffect(listState)
                // A shelf that appears above the first one (e.g. "Continue listening" after loading or the first started book)
                // comes into view if the list showed its start, instead of lying above the edge.
                val shelves = listOfNotNull(
                    "continue".takeIf { continueListening.isNotEmpty() },
                    "series".takeIf { continueSeries.isNotEmpty() },
                    "recent".takeIf { recent.isNotEmpty() },
                )
                ShowStartOnNewFront(shelves, listState, { it }, announce = false)
                LazyColumn(state = listState, contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
                    if (continueListening.isNotEmpty()) {
                        item(key = "continue") {
                            Shelf(
                                stringResource(R.string.spoken_continue),
                                action = {
                                    IconButton(onClick = onOpenQueue) {
                                        Icon(painterResource(DesignR.drawable.ic_list), contentDescription = stringResource(R.string.player_queue))
                                    }
                                },
                            ) {
                                BookRow(continueRow.items, onBookClick, state = continueRow.state, playingId = playingId, onPlay = viewModel::playOrPause)
                            }
                        }
                    }
                    // Next volume of series in which one was recently finished (books_improvements M2).
                    if (continueSeries.isNotEmpty()) {
                        item(key = "series") {
                            Shelf(stringResource(R.string.spoken_continue_series)) {
                                BookRow(
                                    seriesRow.items.map { it.first },
                                    onBookClick,
                                    state = seriesRow.state,
                                    playingId = playingId,
                                    onPlay = viewModel::playOrPause,
                                    volumes = seriesRow.items.mapNotNull { (item, volume) -> volume?.let { item.book.id to volumeNumber(it) } }.toMap(),
                                )
                            }
                        }
                    }
                    if (recent.isNotEmpty()) {
                        item(key = "recent") {
                            Shelf(stringResource(R.string.spoken_recent)) {
                                BookRow(recentRow.items, onBookClick, state = recentRow.state, playingId = playingId, onPlay = viewModel::playOrPause)
                            }
                        }
                    }
                    if (continueState == LoadState.Failed || recentState == LoadState.Failed) {
                        item(key = "failed") { LoadFailedHint() }
                    } else if (showsNoBooksHint(continueState, recentState, hasBooks, hasServer)) {
                        item(key = "empty") { NoBooksHint(signInNeeded, onEditServer, viewModel::syncNow) }
                    }
                    // Audiobooks and radio plays from ARD Sounds (books_improvements M9), like "Add podcast" below the shelves.
                    if (ardSounds) {
                        item(key = "ard-sounds") {
                            FilledTonalButton(onClick = onOpenArdSounds, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                                Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                                Text(stringResource(R.string.ard_books_open), modifier = Modifier.padding(start = 8.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "No books yet" with "Sync now" only for an empty library: if everything is heard or hidden, both shelves are empty as
 * well, and syncing changes nothing (review4 T41).
 */
internal fun showsNoBooksHint(
    continueListening: LoadState<List<*>>,
    recentlyAdded: LoadState<List<*>>,
    hasBooks: LoadState<Boolean>,
    hasServer: Boolean?,
): Boolean = hasServer == true &&
    continueListening.valueOrNull?.isEmpty() == true &&
    recentlyAdded.valueOrNull?.isEmpty() == true &&
    hasBooks.valueOrNull == false

private enum class BookTab(@StringRes val label: Int) {
    ALL(R.string.spoken_tab_all),
    SERIES(R.string.spoken_tab_series),
    AUTHORS(R.string.spoken_tab_authors),
    STATS(R.string.spoken_tab_stats),
}

/**
 * Library in "Books" mode: All (grid), Series and Authors (lists) as with music, plus the statistics; in the header the
 * way to the collected bookmarks ([onOpenBookmarks]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpokenLibraryScreen(
    onBookClick: (Long) -> Unit,
    onGroupClick: (BookGroupKind, String) -> Unit,
    onOpenBookmarks: () -> Unit,
    onSetup: () -> Unit,
    onEditServer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val booksState by viewModel.allBooks.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val signInNeeded by viewModel.signInNeeded.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val shownBooks by viewModel.shownBooks.collectAsStateWithLifecycle()
    val bookSort by viewModel.bookSort.collectAsStateWithLifecycle()
    val bookFilter by viewModel.bookFilter.collectAsStateWithLifecycle()
    val genres by viewModel.genres.collectAsStateWithLifecycle()
    val bookGenre by viewModel.bookGenre.collectAsStateWithLifecycle()
    val libraries by viewModel.libraries.collectAsStateWithLifecycle()
    val bookLibrary by viewModel.bookLibrary.collectAsStateWithLifecycle()
    val seriesSort by viewModel.seriesSort.collectAsStateWithLifecycle()
    val authorSort by viewModel.authorSort.collectAsStateWithLifecycle()
    val series by viewModel.seriesGroups.collectAsStateWithLifecycle()
    val authors by viewModel.authorGroups.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { BookTab.entries.size }
    val scope = rememberCoroutineScope()

    // As in the music library, the tab's sorting is in the app header left of the magnifier, preceded by the bookmarks.
    HeaderAction {
        BookmarksHeaderButton(BookKind.BOOK, onOpenBookmarks)
        if (!booksState.valueOrNull.isNullOrEmpty()) {
            when (BookTab.entries[pagerState.targetPage]) {
                BookTab.ALL -> SortButton(bookSort, BookSort.entries, { stringResource(it.label) }, viewModel::setBookSort)
                BookTab.SERIES -> SortButton(seriesSort, SeriesSort.entries, { stringResource(it.label) }, viewModel::setSeriesSort)
                BookTab.AUTHORS -> SortButton(authorSort, AuthorSort.entries, { stringResource(it.label) }, viewModel::setAuthorSort)
                BookTab.STATS -> Unit
            }
        }
    }

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            booksState == LoadState.Failed -> LoadFailedHint()
            booksState == LoadState.Loading || hasServer == null -> LoadingIndicator()
            booksState.valueOrNull.isNullOrEmpty() -> NoBooksHint(signInNeeded, onEditServer, viewModel::syncNow)
            else -> {
                val all = booksState.valueOrNull.orEmpty()
                PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
                    BookTab.entries.forEachIndexed { index, tab ->
                        // As in the music and podcast libraries: smaller font instead of wrapping.
                        Tab(
                            selected = pagerState.currentPage == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                        ) { TabLabel(stringResource(tab.label)) }
                    }
                }
                HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
                    when (BookTab.entries[page]) {
                        BookTab.ALL -> {
                            val shown = shownBooks ?: all
                            BookGrid(
                                shown,
                                onBookClick,
                                playingId,
                                viewModel::playOrPause,
                                scrollLabel = bookSort.scrollLabel(),
                                pinHeader = true,
                                // The chips stay even if the filter leaves nothing, so it can be cleared.
                                header = {
                                    Column {
                                        BookFilterChips(
                                            bookFilter,
                                            viewModel::setBookFilter,
                                            genre = OptionFilter(genres.map { it to it }, bookGenre, viewModel::setBookGenre),
                                            library = OptionFilter(libraries.takeIf { it.size > 1 }.orEmpty(), bookLibrary, viewModel::setBookLibrary),
                                        )
                                        if (shown.isEmpty()) {
                                            Text(
                                                stringResource(R.string.library_filter_empty),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 16.dp),
                                            )
                                        }
                                    }
                                },
                                // Different order or different filter: back to the top.
                                resetKey = listOf(bookSort, bookFilter, bookGenre, bookLibrary),
                            )
                        }
                        BookTab.SERIES -> SeriesList(series, byName = seriesSort == SeriesSort.NAME, resetKey = seriesSort) {
                            onGroupClick(BookGroupKind.SERIES, it)
                        }
                        BookTab.AUTHORS -> AuthorGrid(authors, byName = authorSort == AuthorSort.NAME, resetKey = authorSort) {
                            onGroupClick(BookGroupKind.AUTHOR, it)
                        }
                        BookTab.STATS -> BookStatsScreen(onBookClick)
                    }
                }
            }
        }
    }
}

/**
 * Books as a grid with fast scroller, with [scrollLabel] including a marker (e.g. initial letter), with [header] a row above it across the
 * full width, with [volumes] the volume per book at the top left (series page). If [resetKey] changes, the grid starts at the top.
 */
@Composable
private fun BookGrid(
    books: List<BookWithProgress>,
    onBookClick: (Long) -> Unit,
    playingId: Long?,
    onPlay: (Long) -> Unit,
    scrollLabel: ((BookWithProgress) -> String)? = null,
    header: (@Composable () -> Unit)? = null,
    volumes: Map<Long, String> = emptyMap(),
    resetKey: Any? = null,
    /** Finished books with a full bar (series page). */
    markFinished: Boolean = false,
    /**
     * Instead of [books], in sections per series (author page): heading tappable ([onSectionClick]), volume on the cover,
     * finished ones with a full bar; the books without a series are called "More books".
     */
    sections: List<SeriesSection>? = null,
    onSectionClick: (String) -> Unit = {},
    /** [header] (chip bar of "All") stays fixed above while scrolling, see [PinnableHeaderBox]. */
    pinHeader: Boolean = false,
) {
    val state = rememberLazyGridState()
    ScrollToTopEffect(state)
    val ordered = remember(books, sections) { sections?.flatMap { it.books } ?: books }
    ShowStartOnNewFront(ordered, state, { it.book.id })
    var shownKey by rememberSaveable { mutableStateOf(resetKey?.toString()) }
    LaunchedEffect(resetKey) {
        if (resetKey?.toString() != shownKey) {
            shownKey = resetKey?.toString()
            state.scrollToItem(0)
        }
    }
    PinnableHeaderBox(header, pinHeader, Modifier.fillMaxSize()) { inlineHeader ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = gridContentPadding(pinnedHeader = header != null && inlineHeader == null),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (inlineHeader != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { inlineHeader() }
            val card: @Composable LazyGridItemScope.(BookWithProgress, String?, Boolean) -> Unit = { item, volume, finished ->
                BookCard(
                    item,
                    onClick = { onBookClick(item.book.id) },
                    modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer).animateItem(),
                    onPlay = { onPlay(item.book.id) },
                    playing = item.book.id == playingId,
                    contentPadding = GridTilePadding,
                    volume = volume,
                    markFinished = finished,
                )
            }
            if (sections == null) {
                items(books, key = { it.book.id }) { item -> card(item, volumes[item.book.id], markFinished) }
            } else {
                sections.forEachIndexed { index, section ->
                    val series = section.series
                    // Without any series, no heading.
                    if (series != null || sections.size > 1) {
                        item(key = "section-$index", span = { GridItemSpan(maxLineSpan) }) {
                            SeriesSectionTitle(series, onClick = series?.let { { onSectionClick(it) } })
                        }
                    }
                    items(section.books, key = { it.book.id }) { item ->
                        val volume = series?.let { name -> parseSeries(item.book.series).firstOrNull { it.name == name }?.volume }
                        card(item, volume?.let(::volumeNumber), series != null)
                    }
                }
            }
        }
        val offset = if (inlineHeader != null) 1 else 0
        LazyGridFastScroller(state, labelAt = scrollLabel?.let { label -> { index -> books.getOrNull((index - offset).coerceAtLeast(0))?.let(label).orEmpty() } })
    }
}

/** Heading of a section of the author page: the name of the series (opens it) or "More books". */
@Composable
private fun SeriesSectionTitle(series: String?, onClick: (() -> Unit)?) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 4.dp, end = 4.dp, top = 12.dp, bottom = 4.dp),
    ) {
        Text(
            series ?: stringResource(R.string.author_more_books),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (onClick != null) {
            Icon(painterResource(DesignR.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

/** Marker for the fast scroller per order; without a sensible marker (date, duration) none. */
private fun BookSort.scrollLabel(): ((BookWithProgress) -> String)? = when (this) {
    BookSort.TITLE -> { item -> foldedLetterOf(item.book.title.sortKey()) }
    BookSort.AUTHOR -> { item -> foldedLetterOf(bookAuthors(item.book.author).firstOrNull().orEmpty().sortKey()) }
    BookSort.YEAR -> { item -> item.book.publishedYear?.take(4) ?: "#" }
    BookSort.RECENTLY_ADDED, BookSort.RECENTLY_PLAYED, BookSort.DURATION -> null
}

private val SeriesSort.label: Int
    get() = when (this) {
        SeriesSort.NAME -> R.string.sort_name
        SeriesSort.RECENTLY_PLAYED -> R.string.sort_recently_played
    }

private val AuthorSort.label: Int
    get() = when (this) {
        AuthorSort.NAME -> R.string.sort_name
        AuthorSort.BOOK_COUNT -> R.string.sort_book_count
    }

/** If the order changes ([resetKey]), the list starts at the top again. */
@Composable
private fun ResetScroll(resetKey: Any?, scrollToTop: suspend () -> Unit) {
    var shownKey by rememberSaveable { mutableStateOf(resetKey?.toString()) }
    LaunchedEffect(resetKey) {
        if (resetKey?.toString() != shownKey) {
            shownKey = resetKey?.toString()
            scrollToTop()
        }
    }
}

private val BookSort.label: Int
    get() = when (this) {
        BookSort.TITLE -> R.string.sort_title
        BookSort.AUTHOR -> R.string.sort_author
        BookSort.RECENTLY_ADDED -> R.string.sort_recently_added
        BookSort.RECENTLY_PLAYED -> R.string.sort_recently_played
        BookSort.DURATION -> R.string.sort_duration
        BookSort.YEAR -> R.string.sort_year
    }

/** A filter with a selection list: [options] as (ID, name), [selected] the selected ID (`null` = all). */
private class OptionFilter(val options: List<Pair<String, String>>, val selected: String?, val onSelect: (String?) -> Unit)

/**
 * Filter of the "All" tab: exactly one is selected; can be swiped sideways if the width isn't enough. Before it, if
 * they exist, chips with a selection list for the library (from two, M3) and the genre (S9); they apply in addition to the filter.
 */
@Composable
private fun BookFilterChips(selected: BookFilter, onSelect: (BookFilter) -> Unit, genre: OptionFilter, library: OptionFilter) {
    val scroll = rememberScrollState()
    Row(
        Modifier.keepHorizontalScroll(scroll).horizontalScroll(scroll).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (library.options.isNotEmpty()) OptionChip(library, stringResource(R.string.book_library), stringResource(R.string.book_library_all))
        if (genre.options.isNotEmpty()) OptionChip(genre, stringResource(R.string.book_genre), stringResource(R.string.book_genre_all))
        BookFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(filter.label)) })
        }
    }
}

/** "Genre ▾" or the selected one; the list starts with [allLabel] ("All genres"). */
@Composable
private fun OptionChip(filter: OptionFilter, label: String, allLabel: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = filter.selected != null,
            onClick = { open = true },
            label = {
                Text(
                    filter.options.firstOrNull { it.first == filter.selected }?.second ?: label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            trailingIcon = { Icon(painterResource(DesignR.drawable.ic_expand_more), contentDescription = null) },
            modifier = Modifier.widthIn(max = 220.dp),
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(allLabel) },
                onClick = {
                    open = false
                    filter.onSelect(null)
                },
            )
            filter.options.forEach { (key, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    trailingIcon = if (key == filter.selected) {
                        { Icon(painterResource(DesignR.drawable.ic_check), contentDescription = null) }
                    } else {
                        null
                    },
                    onClick = {
                        open = false
                        filter.onSelect(key)
                    },
                )
            }
        }
    }
}

private val BookFilter.label: Int
    get() = when (this) {
        BookFilter.ALL -> R.string.episode_filter_all
        BookFilter.NOT_STARTED -> R.string.book_filter_not_started
        BookFilter.STARTED -> R.string.episode_filter_started
        BookFilter.FINISHED -> R.string.spoken_finished
        BookFilter.DOWNLOADED -> R.string.episode_filter_downloaded
    }

/**
 * Authors as a grid, as wide as the book tiles (two per row on a phone): author image (from Audiobookshelf, otherwise from the internet if the option is on), below it
 * name and number of books. Without an image the cover of the first book remains. The fast scroller only shows the letter when sorted by name ([byName]).
 */
@Composable
private fun AuthorGrid(authors: List<BookGroup>, byName: Boolean, resetKey: Any?, onClick: (String) -> Unit) {
    if (authors.isEmpty()) {
        EmptyHint(stringResource(R.string.spoken_no_authors))
        return
    }
    val state = rememberLazyGridState()
    ScrollToTopEffect(state)
    ResetScroll(resetKey) { state.scrollToItem(0) }
    ShowStartOnNewFront(authors, state, { it.name })
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
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
                        // Lies over the book cover; if loading fails, the cover stays visible.
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
        LazyGridFastScroller(state, labelAt = if (byName) { index -> foldedLetterOf(authors.getOrNull(index)?.name.orEmpty().sortKey()) } else null)
    }
}

/**
 * Series with the cover of the volume that comes next (otherwise the first), and their progress: "3 of 7 played"; as long as
 * none is finished, the count. The fast scroller only shows the letter when sorted by name ([byName]).
 */
@Composable
private fun SeriesList(groups: List<SeriesProgress>, byName: Boolean, resetKey: Any?, onClick: (String) -> Unit) {
    if (groups.isEmpty()) {
        EmptyHint(stringResource(R.string.spoken_no_series))
        return
    }
    val state = rememberLazyListState()
    ScrollToTopEffect(state)
    ResetScroll(resetKey) { state.scrollToItem(0) }
    ShowStartOnNewFront(groups, state, { it.name })
    Box(Modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(vertical = 8.dp)) {
            groupedItems(groups, key = { it.name }) { group ->
                ListItem(
                    headlineContent = { Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(seriesState(group)) },
                    leadingContent = {
                        BookArt((group.next ?: group.books.first()).book.id, Modifier.size(48.dp), MaterialTheme.shapes.small)
                    },
                    modifier = Modifier.clickable { onClick(group.name) },
                )
            }
        }
        LazyListFastScroller(state, labelAt = if (byName) { index -> foldedLetterOf(groups.getOrNull(index)?.name.orEmpty().sortKey()) } else null)
    }
}

/** Header of the series page: progress of the series and "Continue with volume 4" or "Start with volume 1" (plays it). */
@Composable
private fun SeriesHeader(series: SeriesProgress, playingId: Long?, onPlay: (Long) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(seriesState(series), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val next = series.next
        if (next != null && next.book.id != playingId) {
            val volume = series.nextVolume?.let(::volumeNumber)
            val start = series.step == SeriesStep.START
            val label = when {
                volume != null -> stringResource(if (start) R.string.series_start_with else R.string.series_continue_with, volume)
                else -> stringResource(if (start) R.string.series_start_with_title else R.string.series_continue_with_title, next.book.title)
            }
            FilledTonalButton(onClick = { onPlay(next.book.id) }, modifier = Modifier.fillMaxWidth()) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
                Text(label, modifier = Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Header of the author page: the author image large (omitted if missing) and the description from Audiobookshelf, truncated until
 * tapped. On wide windows the description sits next to the image.
 */
@Composable
private fun AuthorHeader(name: String, description: String?) {
    var hasImage by remember(name) { mutableStateOf(true) }
    val image: @Composable (Modifier) -> Unit = { modifier ->
        if (hasImage) {
            AsyncImage(
                model = AppUris.authorImage(name),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                onError = { hasImage = false },
                modifier = modifier.size(AuthorImageSize).clip(RoundedCornerShape(16.dp)),
            )
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        if (maxWidth >= AuthorImageSize * 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                image(Modifier)
                if (description != null) CollapsibleText(description, Modifier.weight(1f))
            }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                image(Modifier)
                if (description != null) CollapsibleText(description, Modifier.fillMaxWidth())
            }
        }
    }
}

private val AuthorImageSize = 200.dp

/** Text truncated to [CollapsedLines] lines until tapped; "More" or "Less" below it if it is longer. */
@Composable
private fun CollapsibleText(text: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflowing by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        Text(
            rememberDescription(text),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else CollapsedLines,
            overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflowing = it.hasVisualOverflow },
            modifier = Modifier.clickable(enabled = overflowing || expanded) { expanded = !expanded },
        )
        if (overflowing || expanded) {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text(stringResource(if (expanded) R.string.text_show_less else R.string.text_show_more))
            }
        }
    }
}

private const val CollapsedLines = 5

/** "3 of 7 played", "All 7 played" or, without a finished volume, "7 books". */
@Composable
private fun seriesState(series: SeriesProgress): String = when {
    series.finished == 0 -> pluralStringResource(R.plurals.book_count, series.total, series.total)
    series.next == null -> stringResource(R.string.series_all_finished, series.total)
    else -> stringResource(R.string.series_finished_count, series.finished, series.total)
}

/** All books of a series, an author or a narrator. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookGroupScreen(
    kind: BookGroupKind,
    name: String,
    onBack: () -> Unit,
    onBookClick: (Long) -> Unit,
    onGroupClick: (BookGroupKind, String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val booksState by viewModel.allBooks.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val group = remember(booksState, kind, name) {
        booksState.valueOrNull?.groups(kind)?.firstOrNull { if (kind == BookGroupKind.GENRE) sameGenre(it.name, name) else it.name == name }
    }
    val description by remember(kind, name) {
        if (kind == BookGroupKind.AUTHOR) viewModel.authorDescription(name) else flowOf(null)
    }.collectAsStateWithLifecycle(null)
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
                // On series pages the volume is on the cover.
                val volumes = remember(group, kind) {
                    if (kind != BookGroupKind.SERIES) {
                        emptyMap()
                    } else {
                        group.books.mapNotNull { item ->
                            parseSeries(item.book.series).firstOrNull { it.name == name }?.volume?.let { item.book.id to volumeNumber(it) }
                        }.toMap()
                    }
                }
                // Series: at the top the progress and the volume that comes next (books_improvements M2); finished volumes with a full bar.
                val series = remember(group, kind) { if (kind == BookGroupKind.SERIES) seriesProgress(name, group.books) else null }
                if (kind == BookGroupKind.AUTHOR) {
                    // Authors: large image and description, the books by series (books_improvements M8).
                    val sections = remember(group) { group.books.seriesSections() }
                    BookGrid(
                        group.books,
                        onBookClick,
                        playingId,
                        viewModel::playOrPause,
                        header = { AuthorHeader(name, description) },
                        sections = sections,
                        onSectionClick = { onGroupClick(BookGroupKind.SERIES, it) },
                    )
                } else {
                    BookGrid(
                        group.books,
                        onBookClick,
                        playingId,
                        viewModel::playOrPause,
                        volumes = volumes,
                        header = series?.let { { SeriesHeader(it, playingId, viewModel::playOrPause) } },
                        markFinished = series != null,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoServerHint(onSetup: () -> Unit) {
    EmptyAreaHint(SpokenMode.BOOKS, stringResource(R.string.spoken_no_server), stringResource(R.string.spoken_setup), onSetup)
}

/**
 * Server present, but no books. If a server is missing its login or API key ([signInNeeded]), that is usually the
 * reason: then the way to the server's form. Otherwise "Sync now".
 */
@Composable
private fun NoBooksHint(signInNeeded: SpokenServer?, onEditServer: (String) -> Unit, onSync: () -> Unit) {
    if (signInNeeded != null) {
        EmptyAreaHint(
            SpokenMode.BOOKS,
            stringResource(if (signInNeeded.openId) R.string.spoken_login_expired else R.string.spoken_key_missing, signInNeeded.name),
            stringResource(if (signInNeeded.openId) R.string.spoken_sign_in_again else R.string.spoken_enter_key),
            onSetup = { onEditServer(signInNeeded.serverId) },
        )
    } else {
        EmptyAreaHint(SpokenMode.BOOKS, stringResource(R.string.spoken_no_books), stringResource(R.string.spoken_abs_sync), onSync)
    }
}

@Composable
internal fun BookRow(
    books: List<BookWithProgress>,
    onBookClick: (Long) -> Unit,
    state: LazyListState = rememberLazyListState(),
    playingId: Long? = null,
    onPlay: ((Long) -> Unit)? = null,
    /** Volume per book at the top left of the cover ("Continue series" shelf). */
    volumes: Map<Long, String> = emptyMap(),
    /** "Recently added" of the podcasts: the long-press menu offers to remove an episode from it. */
    inRecent: Boolean = false,
) {
    val sameDay = remember(books) { episodesSharingDay(books.map { it.book }) }
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
                withTime = item.book.id in sameDay,
                volume = volumes[item.book.id],
                inRecent = inRecent,
            )
        }
    }
}

/** Row from [rememberFrontRow]: its scroll state and its entries in the order it currently shows. */
class FrontRow<T>(val state: LazyListState, val items: List<T>)

/**
 * Row whose new items are at the front ("Continue listening", "Recently added", music queue; [key] provides the ID of an
 * entry): if an entry is new or moves to the front from further back (e.g. started in the row itself), the row keeps
 * its order for [FRONT_HOLD] – the tapped tile stays in place and can be paused again right away.
 * Then the row glides to its start ([glideToStart]), and the entry moves to the front. Without this a
 * lazy row would hold on to the previous first entry, and the new one would lie outside on the left. If only the frontmost drops out (fully
 * played, started, removed from the queue), the rest moves up immediately with a glide ([rowItemAnimation]); a
 * jump to the start would cut that animation off. The front entries survive leaving the screen: back
 * from a book page, the row stays where it was as long as nothing new is at the front, otherwise it shows its start right away.
 * If the user had scrolled the row away from its start and an entry arrives that wasn't in it before (a sync, a refresh),
 * the row stays where it is and a snackbar offers to show its start ([rememberNewFrontHint]); with [announce] off (queue)
 * it glides there as well.
 */
@Composable
fun <T> rememberFrontRow(items: List<T>, announce: Boolean = true, key: (T) -> Long): FrontRow<T> {
    val state = rememberLazyListState()
    val saved = rememberSaveable(saver = FrontIdsSaver) { FrontIds(emptyList()) }
    val ids = items.map(key)
    // Last adopted order; null until the row is drawn here for the first time.
    var shown by remember { mutableStateOf<List<Long>?>(null) }
    val atStart by remember(state) { derivedStateOf { state.atStart() } }
    // Read before the row has laid out the new entries: afterwards it has already shifted to the previous first one.
    val startBefore = remember(ids) { atStart }
    val quiet = announce && !startBefore && shown?.let { ids.firstOrNull() !in it } == true
    val held = shown?.takeIf { !quiet && frontChange(it, ids) == FrontChange.MOVED }
    val visible = if (held == null) items else items.associateBy(key).let { byId -> held.mapNotNull(byId::get) }
    val hint = rememberNewFrontHint { state.glideToStart() }
    LaunchedEffect(ids) {
        val before = shown
        if (before == null) {
            if (frontChange(saved.ids, ids) != FrontChange.NONE) state.scrollToItem(0)
        } else if (frontChange(before, ids) != FrontChange.NONE) {
            if (quiet) {
                // The row already shows the new order and stays at the entry the user is looking at.
                hint.announceSoon { state.atStart() }
            } else {
                delay(FRONT_HOLD)
                // Together with the new order, otherwise the row would hold on to the previous first one.
                if (state.glideToStart()) state.requestScrollToItem(0)
            }
        }
        shown = ids
    }
    SideEffect { saved.ids = visible.take(2).map(key) }
    return FrontRow(state, visible)
}

/**
 * List or grid whose start should come into view when something is new at the front ([items] in the order shown, [key]
 * their ID): an entry found by a sync or a manual refresh, one that moved to the front from further back (played, other
 * sorting) or another filter. A lazy list would otherwise hold on to its previous first entry, and the new ones would lie
 * invisibly above the edge (the counterpart of [rememberFrontRow], without the hold).
 *
 * If the list showed its start, it jumps there again right away. If the user had scrolled away, it stays where it is; for an
 * entry that wasn't in the list before, a snackbar offers to show the start ([rememberNewFrontHint]; not with [announce] off,
 * for lists of shelves). If only the frontmost drops out and the next moves up, nothing happens. Survives leaving the screen:
 * back on it, the list shows its start if it was there and something arrived at the front in the meantime.
 *
 * [resetKey]: the sort and filter of the list. If it changes, the list goes to its start without a hint, wherever it stood: the
 * user chose another order or selection, and its first entries would otherwise lie above the edge (review3 Q4), or a
 * filter that lets more through would be announced as new (Q5).
 */
@Composable
fun <T> ShowStartOnNewFront(items: List<T>, state: LazyListState, key: (T) -> Any, announce: Boolean = true, resetKey: Any? = null) {
    val atStart = remember(state) { derivedStateOf { state.atStart() } }
    ShowStartOnNewFront(items, key, atStart, announce, resetKey, { state.atStart() }, { state.scrollToItem(0) }) { state.animateScrollToItem(0) }
}

/** [ShowStartOnNewFront] for a grid. */
@Composable
fun <T> ShowStartOnNewFront(items: List<T>, state: LazyGridState, key: (T) -> Any, announce: Boolean = true, resetKey: Any? = null) {
    val atStart = remember(state) { derivedStateOf { state.atStart() } }
    ShowStartOnNewFront(items, key, atStart, announce, resetKey, { state.atStart() }, { state.scrollToItem(0) }) { state.animateScrollToItem(0) }
}

@Composable
private fun <T> ShowStartOnNewFront(
    items: List<T>,
    key: (T) -> Any,
    atStart: State<Boolean>,
    announce: Boolean,
    resetKey: Any?,
    isAtStart: () -> Boolean,
    jumpToStart: suspend () -> Unit,
    glideToStart: suspend () -> Unit,
) {
    val ids = remember(items) { items.map { key(it).toString() } }
    val front = ids.take(2)
    var shown by rememberSaveable { mutableStateOf(front) }
    // All IDs of the previous draw, to tell a new entry from one that moved up; unknown after returning to the screen.
    val previous = remember { LastIds() }
    val before = previous.ids
    val known = remember(ids) { ids.toHashSet() }
    SideEffect { previous.ids = known }
    // Read before the list has laid out the new entries: afterwards it has already shifted to the previous first one.
    val startBefore = remember(ids) { atStart.value }
    // Sort and filter of the previous draw; a new screen starts with the current ones. From `null` (still loading) is no change:
    // otherwise a restored scroll position would be lost as soon as the settings arrive.
    val lastReset = remember { LastResetKey(resetKey) }
    val reset = lastReset.key != null && lastReset.key != resetKey
    SideEffect { lastReset.key = resetKey }
    val hint = rememberNewFrontHint(glideToStart)
    LaunchedEffect(front, resetKey) {
        // All IDs, not just the front two: whether the previous first one moved further back or is gone decides.
        val change = frontChange(shown, ids)
        shown = front
        when {
            reset -> jumpToStart()
            change == FrontChange.NONE -> Unit
            startBefore -> jumpToStart()
            // Another sort or filter took the list to its start ([resetKey], ResetScroll); then nothing is offered here.
            announce && before != null && ids.first() !in before -> hint.announceSoon(isAtStart)
        }
    }
}

/** All IDs of a list at its last draw (for [ShowStartOnNewFront]). */
private class LastIds(var ids: Set<String>? = null)

/** The sort and filter of a list at its last draw (for [ShowStartOnNewFront]). */
private class LastResetKey(var key: Any?)

/**
 * Snackbar for a list or row the user had scrolled away from when something new arrived at its start: it says so and
 * [scrollToStart] on tap. It stays until it expires, so the result of a refresh doesn't displace it but follows it. It
 * leaves with its list: otherwise it showed on another page, and "Show" did nothing there (review3 Q25).
 */
@Composable
private fun rememberNewFrontHint(scrollToStart: suspend () -> Unit): NewFrontHint {
    val snackbars = LocalSnackbars.current
    val text by rememberUpdatedState(stringResource(R.string.new_at_start))
    val action by rememberUpdatedState(stringResource(R.string.new_at_start_show))
    val scope = rememberCoroutineScope()
    val scroll by rememberUpdatedState(scrollToStart)
    val hint = remember(snackbars, scope) { NewFrontHint(snackbars, scope, { text }, { action }) { scroll() } }
    DisposableEffect(hint) { onDispose(hint::withdraw) }
    return hint
}

/** The "New at the start" hint of one list ([rememberNewFrontHint]). */
private class NewFrontHint(
    private val snackbars: Snackbars,
    private val scope: CoroutineScope,
    private val text: () -> String,
    private val action: () -> String,
    private val scrollToStart: suspend () -> Unit,
) {
    /** One message per list: shown or waiting, the queue doesn't take it twice, and [withdraw] finds it. */
    private var message: SnackbarMessage? = null

    /**
     * Offers the start once the list has settled ([NEW_FRONT_SETTLE]) and still isn't there. In the list's own scope, not in
     * the effect that noticed the change: a further change within the wait restarted it and lost the hint (review3 Q25).
     */
    fun announceSoon(isAtStart: () -> Boolean) {
        scope.launch {
            delay(NEW_FRONT_SETTLE)
            if (!isAtStart()) snackbars.show(current())
        }
    }

    fun withdraw() {
        message?.let(snackbars::withdraw)
    }

    private fun current(): SnackbarMessage = message?.takeIf { it.text == text() && it.actionLabel == action() }
        ?: SnackbarMessage(text(), action(), onAction = { scope.launch { scrollToStart() } }, keep = true).also { message = it }
}

private fun LazyListState.atStart() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

private fun LazyGridState.atStart() = firstVisibleItemIndex == 0 && firstVisibleItemScrollOffset == 0

/** What changed at the front of a row ([rememberFrontRow]). */
internal enum class FrontChange {
    /** The same one is at the front, or only the frontmost dropped out and the second moves up. */
    NONE,

    /** An entry is new or moved to the front from further back; the previous first one is still there. */
    MOVED,

    /** Another one is at the front, the previous first one is gone. */
    REPLACED,
}

internal fun <T> frontChange(before: List<T>, ids: List<T>): FrontChange {
    val first = ids.firstOrNull()
    val oldFirst = before.firstOrNull()
    return when {
        first == null || oldFirst == null || first == oldFirst -> FrontChange.NONE
        oldFirst in ids -> FrontChange.MOVED
        first == before.getOrNull(1) -> FrontChange.NONE
        else -> FrontChange.REPLACED
    }
}

/**
 * Glides calmly to the start of the row (the tiles have equal width); false if the user scrolls during it.
 * [LazyListState.animateScrollToItem] would be faster than the eye can follow and can't be slowed down.
 */
private suspend fun LazyListState.glideToStart(): Boolean {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull() ?: return true
    val distance = firstVisibleItemIndex * (item.size + info.mainAxisItemSpacing) + firstVisibleItemScrollOffset
    if (distance <= 0) return true
    return try {
        animateScrollBy(-distance.toFloat(), tween(FRONT_GLIDE_MS, easing = FastOutSlowInEasing))
        true
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        false
    }
}

private val FRONT_HOLD = 2.seconds

/** Time for a sort or filter change to take a list to its start before a hint about new entries is due. */
private val NEW_FRONT_SETTLE = 500.milliseconds
private const val FRONT_GLIDE_MS = 800

/** The first two entries of a row at the last draw (for [rememberFrontRow]). */
private class FrontIds(var ids: List<Long>)

private val FrontIdsSaver = Saver<FrontIds, LongArray>(save = { it.ids.toLongArray() }, restore = { FrontIds(it.toList()) })

/**
 * Tile of a horizontal row: a removed entry dissolves quickly, the tiles to its right then glide
 * to the left, as if the row were contracting.
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
 * Cover with progress bar, title and author below; with [onPlay] a play (with [playing] pause) button sits at the bottom right
 * of the cover, the download marker at the bottom left ([BookDownloadBadge]), the volume at the top left ([volume], on series pages).
 * With [selection] the long-press menu offers "Select" (concept 12.5); during the selection a tap adds or removes
 * the tile.
 */
@Composable
fun BookCard(
    item: BookWithProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
    contentPadding: Dp = 0.dp,
    /** Time of day next to the episode's date ([episodesSharingDay]). */
    withTime: Boolean = false,
    volume: String? = null,
    selection: EpisodeSelection? = null,
    /** Finished: full bar instead of none (series page). */
    markFinished: Boolean = false,
    /** The tile stands in "Recently added" of the podcasts ([EpisodeMenu]). */
    inRecent: Boolean = false,
) {
    // Context menu via long press, for episodes and books.
    var menu by remember { mutableStateOf(false) }
    val episode = item.isEpisode
    val selecting = selection?.active == true
    val selected = selection != null && item.book.id in selection
    Column(
        modifier
            .combinedClickable(
                onClick = { if (selecting) selection.toggle(item.book.id) else onClick() },
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { if (selecting) selection.toggle(item.book.id) else menu = true },
            )
            .padding(contentPadding),
    ) {
        EpisodeMenu(
            item,
            expanded = menu,
            onDismiss = { menu = false },
            onSelect = selection?.let { { it.toggle(item.book.id) } },
            inRecent = inRecent,
        )
        Box {
            BookArt(item.book.id, Modifier.fillMaxWidth())
            SelectedMark(selected, RoundedCornerShape(8.dp), Modifier.matchParentSize())
            BookDownloadBadge(item.book.id, Modifier.align(Alignment.BottomStart).padding(6.dp))
            if (volume != null) {
                CoverPill(volume, stringResource(R.string.book_volume, volume), Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (onPlay != null && !selecting) {
                PlayPauseButton(
                    playing,
                    onPlay,
                    Modifier.align(Alignment.BottomEnd).padding(6.dp),
                    playDescription = if (episode) R.string.podcast_play_episode else R.string.spoken_play,
                )
            }
        }
        // All tiles of a row have the same height, regardless of whether started and how long the title is: otherwise the content
        // below jumps when other tiles become visible while scrolling. Hence space for the bar and two title lines.
        val progress = item.progress
        Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(PROGRESS_HEIGHT)) {
            if (progress != null && progress.positionMs > 0 && !progress.finished) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
            } else if (markFinished && progress?.finished == true) {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth(), drawStopIndicator = {})
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
            item.book.authorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Publication date of the episode (for books `addedAt` is only the time of the sync); the row stays as a placeholder.
        if (episode) {
            Text(
                item.book.addedAt.takeIf { it > 0 }?.let { formatEpisodeDate(it, withTime) }.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 1,
                maxLines = 1,
            )
        }
    }
}

private val PROGRESS_HEIGHT = 4.dp

/** Small badge with a number on a cover (volume of a series, new episodes of a subscription); [description] for TalkBack. */
@Composable
internal fun CoverPill(text: String, description: String, modifier: Modifier = Modifier) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.tertiary,
        contentColor = MaterialTheme.colorScheme.onTertiary,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}
