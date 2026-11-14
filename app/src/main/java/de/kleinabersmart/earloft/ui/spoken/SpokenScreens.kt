package de.kleinabersmart.earloft.ui.spoken

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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import de.kleinabersmart.earloft.core.model.BookFilter
import de.kleinabersmart.earloft.core.model.BookSort
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
import de.kleinabersmart.earloft.core.data.spoken.narratorGroups
import de.kleinabersmart.earloft.core.data.spoken.seriesGroups
import de.kleinabersmart.earloft.navigation.BookGroupKind
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.letterOf
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
}

/** Start im Modus „Bücher“: weiterhören und neu hinzugekommene Bücher. */
@Composable
fun SpokenHomeScreen(
    onBookClick: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    onSetup: () -> Unit,
    onEditServer: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpokenLibraryViewModel = hiltViewModel(),
) {
    val continueState by viewModel.continueListening.collectAsStateWithLifecycle()
    val recentState by viewModel.recentlyAdded.collectAsStateWithLifecycle()
    val hasServer by viewModel.hasServer.collectAsStateWithLifecycle()
    val signInNeeded by viewModel.signInNeeded.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val continueListening = continueState.valueOrNull.orEmpty()
    val recent = recentState.valueOrNull.orEmpty()
    val rowState = rememberFrontRowState(continueListening.map { it.book.id })
    val recentRowState = rememberFrontRowState(recent.map { it.book.id })

    Column(modifier.fillMaxSize()) {
        when {
            hasServer == false -> NoServerHint(onSetup)
            else -> LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
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
                            BookRow(continueListening, onBookClick, state = rowState, playingId = playingId, onPlay = viewModel::playOrPause)
                        }
                    }
                }
                if (recent.isNotEmpty()) {
                    item(key = "recent") {
                        Shelf(stringResource(R.string.spoken_recent)) {
                            BookRow(recent, onBookClick, state = recentRowState, playingId = playingId, onPlay = viewModel::playOrPause)
                        }
                    }
                }
                if (continueState == LoadState.Failed || recentState == LoadState.Failed) {
                    item(key = "failed") { LoadFailedHint() }
                } else if (continueState is LoadState.Ready && recentState is LoadState.Ready &&
                    continueListening.isEmpty() && recent.isEmpty() && hasServer == true
                ) {
                    item(key = "empty") { NoBooksHint(signInNeeded, onEditServer, viewModel::syncNow) }
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
    val pagerState = rememberPagerState { BookTab.entries.size }
    val scope = rememberCoroutineScope()

    // Wie in der Musik-Bibliothek steht die Sortierung des Reiters „Alle“ im Kopf der App links von der Lupe.
    HeaderAction {
        if (BookTab.entries[pagerState.targetPage] == BookTab.ALL && !booksState.valueOrNull.isNullOrEmpty()) {
            SortButton(bookSort, BookSort.entries, { stringResource(it.label) }, viewModel::setBookSort)
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
                        BookTab.ALL -> {
                            val shown = shownBooks ?: all
                            BookGrid(
                                shown,
                                onBookClick,
                                playingId,
                                viewModel::playOrPause,
                                scrollLabel = bookSort.scrollLabel(),
                                // Die Chips bleiben stehen, auch wenn der Filter nichts übrig lässt, damit man ihn lösen kann.
                                header = {
                                    Column {
                                        BookFilterChips(bookFilter, viewModel::setBookFilter)
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
                                // Andere Reihenfolge oder anderer Filter: wieder von oben.
                                resetKey = bookSort to bookFilter,
                            )
                        }
                        BookTab.SERIES -> BookGroupList(series) { onGroupClick(BookGroupKind.SERIES, it) }
                        BookTab.AUTHORS -> AuthorGrid(authors) { onGroupClick(BookGroupKind.AUTHOR, it) }
                        BookTab.STATS -> BookStatsScreen(onBookClick)
                    }
                }
            }
        }
    }
}

/**
 * Bücher als Raster; mit [scrollLabel] samt Schnellscroller (z. B. Anfangsbuchstabe), mit [header] einer Zeile darüber über die
 * ganze Breite, mit [volumes] dem Band je Buch oben links (Serienseite). Ändert sich [resetKey], beginnt das Raster oben.
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
) {
    val state = rememberLazyGridState()
    var shownKey by rememberSaveable { mutableStateOf(resetKey?.toString()) }
    LaunchedEffect(resetKey) {
        if (resetKey?.toString() != shownKey) {
            shownKey = resetKey?.toString()
            state.scrollToItem(0)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(150.dp),
            state = state,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (header != null) item(key = "header", span = { GridItemSpan(maxLineSpan) }) { header() }
            items(books, key = { it.book.id }) { item ->
                BookCard(
                    item,
                    onClick = { onBookClick(item.book.id) },
                    modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer).animateItem(),
                    onPlay = { onPlay(item.book.id) },
                    playing = item.book.id == playingId,
                    contentPadding = GridTilePadding,
                    volume = volumes[item.book.id],
                )
            }
        }
        if (scrollLabel != null) {
            val offset = if (header != null) 1 else 0
            LazyGridFastScroller(state, labelAt = { index -> books.getOrNull((index - offset).coerceAtLeast(0))?.let(scrollLabel).orEmpty() })
        }
    }
}

/** Marke für den Schnellscroller je Reihenfolge; ohne sinnvolle Marke (Datum, Dauer) keiner. */
private fun BookSort.scrollLabel(): ((BookWithProgress) -> String)? = when (this) {
    BookSort.TITLE -> { item -> letterOf(item.book.title.sortKey()) }
    BookSort.AUTHOR -> { item -> letterOf(bookAuthors(item.book.author).firstOrNull().orEmpty().sortKey()) }
    BookSort.YEAR -> { item -> item.book.publishedYear?.take(4) ?: "#" }
    BookSort.RECENTLY_ADDED, BookSort.RECENTLY_PLAYED, BookSort.DURATION -> null
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

/** Filter des Reiters „Alle“: genau einer ist gewählt; lässt sich seitlich wischen, wenn die Breite nicht reicht. */
@Composable
private fun BookFilterChips(selected: BookFilter, onSelect: (BookFilter) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        Modifier.keepHorizontalScroll(scroll).horizontalScroll(scroll).padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BookFilter.entries.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(filter.label)) })
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
 * Autoren als Raster, zwei pro Reihe: Autorenbild (von Audiobookshelf, sonst aus dem Internet, wenn die Option an ist), darunter
 * Name und Anzahl der Bücher. Ohne Bild bleibt das Cover des ersten Buchs.
 */
@Composable
private fun AuthorGrid(authors: List<BookGroup>, onClick: (String) -> Unit) {
    if (authors.isEmpty()) {
        EmptyHint(stringResource(R.string.spoken_no_authors))
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
        EmptyHint(stringResource(R.string.spoken_no_series))
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

/** Alle Bücher einer Serie, eines Autors oder eines Sprechers. */
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
                // Auf Serienseiten steht der Band auf dem Cover.
                val volumes = remember(group, kind) {
                    if (kind != BookGroupKind.SERIES) {
                        emptyMap()
                    } else {
                        group.books.mapNotNull { item ->
                            parseSeries(item.book.series).firstOrNull { it.name == name }?.sequence?.let { item.book.id to volumeNumber(it) }
                        }.toMap()
                    }
                }
                BookGrid(group.books, onBookClick, playingId, viewModel::playOrPause, volumes = volumes)
            }
        }
    }
}

@Composable
private fun NoServerHint(onSetup: () -> Unit) {
    EmptyAreaHint(SpokenMode.BOOKS, stringResource(R.string.spoken_no_server), stringResource(R.string.spoken_setup), onSetup)
}

/**
 * Server da, aber keine Bücher. Fehlt einem Server die Anmeldung bzw. der API-Key ([signInNeeded]), ist das meist der
 * Grund: dann der Weg ins Formular des Servers. Sonst „Jetzt abgleichen“.
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
) {
    LazyRow(
        state = state,
        modifier = Modifier.keepHorizontalScroll(state),
        contentPadding = PaddingValues(horizontal = ShelfInset),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val sameDay = episodesSharingDay(books.map { it.book })
        items(books, key = { it.book.id }) { item ->
            BookCard(
                item,
                onClick = { onBookClick(item.book.id) },
                modifier = Modifier.width(140.dp).rowItemAnimation(this),
                onPlay = onPlay?.let { play -> { play(item.book.id) } },
                playing = item.book.id == playingId,
                withTime = item.book.id in sameDay,
            )
        }
    }
}

/**
 * Zustand einer Zeile, deren Neues vorn steht („Weiterhören“, „Zuletzt hinzugefügt“; [ids] in ihrer Reihenfolge): Kommt
 * ein Eintrag neu oder von weiter hinten nach vorn, zeigt die Zeile wieder ihren Anfang – eine Lazy-Zeile hielte sonst
 * den bisher ersten Eintrag fest, und der neue läge links außerhalb. Fällt nur der vorderste weg (zu Ende gehört,
 * angefangen, aus der Warteschlange genommen), rückt der Rest gleitend nach ([rowItemAnimation]); ein Sprung an den
 * Anfang schnitte diese Animation ab. Die vorderen Einträge überstehen das Verlassen des Bildschirms: Zurück von einer
 * Buchseite bleibt die Zeile, wo sie war, solange vorn nichts Neues steht.
 */
@Composable
fun rememberFrontRowState(ids: List<Long>): LazyListState {
    val state = rememberLazyListState()
    val previous = rememberSaveable(saver = FrontIdsSaver) { FrontIds(emptyList()) }
    val before = previous.ids
    val first = ids.firstOrNull()
    LaunchedEffect(first) {
        if (first != before.firstOrNull() && first != before.getOrNull(1)) state.scrollToItem(0)
    }
    SideEffect { previous.ids = ids.take(2) }
    return state
}

/** Die ersten beiden Einträge einer Zeile beim letzten Zeichnen (für [rememberFrontRowState]). */
private class FrontIds(var ids: List<Long>)

private val FrontIdsSaver = Saver<FrontIds, LongArray>(save = { it.ids.toLongArray() }, restore = { FrontIds(it.toList()) })

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
 * auf dem Cover, unten links die Download-Markierung ([BookDownloadBadge]), oben links der Band ([volume], auf Serienseiten).
 * Mit [selection] bietet das Langklick-Menü „Auswählen“ (Konzept 12.5); während der Auswahl schaltet ein Tipp die Kachel
 * dazu oder weg.
 */
@Composable
fun BookCard(
    item: BookWithProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: (() -> Unit)? = null,
    playing: Boolean = false,
    contentPadding: Dp = 0.dp,
    /** Uhrzeit beim Datum der Folge ([episodesSharingDay]). */
    withTime: Boolean = false,
    volume: String? = null,
    selection: EpisodeSelection? = null,
) {
    // Kontextmenü per Langklick, bei Folgen und Büchern.
    var menu by remember { mutableStateOf(false) }
    val episode = item.isEpisode
    val selecting = selection?.active == true
    val selected = selection != null && item.book.id in selection
    Column(
        modifier
            .combinedClickable(
                onClick = { if (selecting) selection.toggle(item.book.id) else onClick() },
                onLongClick = { if (selecting) selection.toggle(item.book.id) else menu = true },
            )
            .padding(contentPadding),
    ) {
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = selection?.let { { it.toggle(item.book.id) } })
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
            item.book.authorLine,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            minLines = 1,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // Erscheinungsdatum der Folge (bei Büchern ist `addedAt` nur der Zeitpunkt des Abgleichs); die Zeile bleibt als Platz stehen.
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

/** Kleines Schild mit einer Zahl auf einem Cover (Band einer Serie, neue Folgen eines Abos); [description] für TalkBack. */
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
