package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.text.KeyboardActions
import de.kleinabersmart.earloft.ui.components.ScrollToTopEffect
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.ui.res.pluralStringResource
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.EpisodeFilter
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import de.kleinabersmart.earloft.core.model.FeedSort
import de.kleinabersmart.earloft.ui.components.FastScrollLazyColumn
import de.kleinabersmart.earloft.ui.components.HeaderAction
import de.kleinabersmart.earloft.ui.components.LazyGridFastScroller
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.foldedLetterOf
import de.kleinabersmart.earloft.core.common.sortKey
import de.kleinabersmart.earloft.ui.components.SortButton
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.ui.permission.openAppSettings
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.ShareButton
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.download.EpisodeDownloadProgress
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsPageUrl
import de.kleinabersmart.earloft.core.network.podcast.ardsounds.ardSoundsShowId
import de.kleinabersmart.earloft.core.model.FeedError
import de.kleinabersmart.earloft.ui.LoadState
import de.kleinabersmart.earloft.ui.components.EmptyAreaHint
import de.kleinabersmart.earloft.core.model.SpokenMode
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadableList
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.SectionHeader
import de.kleinabersmart.earloft.ui.components.CoverOutlineWidth
import de.kleinabersmart.earloft.ui.components.GridTilePadding
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.CardGap
import de.kleinabersmart.earloft.ui.components.cardItems
import de.kleinabersmart.earloft.ui.components.Shelf
import de.kleinabersmart.earloft.ui.components.coverOutlineColor
import de.kleinabersmart.earloft.ui.components.gridTile
import de.kleinabersmart.earloft.ui.components.ShelfInset
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.player.LocalMiniPlayerScrollHide
import de.kleinabersmart.earloft.ui.library.TabLabel
import de.kleinabersmart.earloft.ui.settings.SwitchRow
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Start in "Podcasts" mode: Continue listening (started episodes, the most recently played on the left, new ones at the end), recently
 * added episodes and the subscriptions.
 */
@Composable
fun PodcastHomeScreen(
    onEpisodeClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onAddPodcast: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val episodes by viewModel.continueListening.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val feedSort by viewModel.feedSort.collectAsStateWithLifecycle()
    val newEpisodeCounts by viewModel.newEpisodeCounts.collectAsStateWithLifecycle()
    val continueRow = rememberFrontRow(episodes) { it.book.id }
    val recentRow = rememberFrontRow(recent) { it.book.id }
    val listState = rememberLazyListState()
    ScrollToTopEffect(listState)
    // A shelf that appears above the first one ("Recently added" or "Continue listening" after loading, a refresh or the first
    // started episode) comes into view if the list showed its start, instead of lying above the edge.
    val shelves = listOfNotNull("continue".takeIf { episodes.isNotEmpty() }, "recent".takeIf { recent.isNotEmpty() }, "subscriptions")
    ShowStartOnNewFront(shelves, listState, { it }, announce = false)
    LazyColumn(modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
        if (episodes.isNotEmpty()) {
            item(key = "continue") {
                Shelf(
                    stringResource(R.string.spoken_continue),
                    action = {
                        IconButton(onClick = onOpenQueue) {
                            Icon(painterResource(DesignR.drawable.ic_list), contentDescription = stringResource(R.string.player_queue))
                        }
                    },
                ) {
                    val sameDay = remember(continueRow.items) { episodesSharingDay(continueRow.items.map { it.book }) }
                    LazyRow(
                        state = continueRow.state,
                        modifier = Modifier.keepHorizontalScroll(continueRow.state),
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(continueRow.items, key = { it.book.id }) { item ->
                            BookCard(
                                item,
                                onClick = { onEpisodeClick(item.book.id) },
                                modifier = Modifier.width(140.dp).rowItemAnimation(this),
                                onPlay = { viewModel.playOrPause(item.book.id) },
                                playing = item.book.id == playingId,
                                withTime = item.book.id in sameDay,
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Shelf(stringResource(R.string.spoken_recent), action = { ClearRecentButton(viewModel::clearRecent) }) {
                    BookRow(
                        recentRow.items,
                        onEpisodeClick,
                        state = recentRow.state,
                        playingId = playingId,
                        onPlay = viewModel::playOrPause,
                        inRecent = true,
                    )
                }
            }
        }
        // Without subscriptions: "Add podcast" first, below it the way to hide the area.
        if (feeds.valueOrNull?.isEmpty() == true) {
            item(key = "empty") {
                EmptyAreaHint(SpokenMode.PODCASTS, stringResource(R.string.podcast_none), stringResource(R.string.podcast_add), onAddPodcast)
            }
            return@LazyColumn
        }
        item(key = "subscriptions") {
            Shelf(stringResource(R.string.podcast_subscriptions), action = { FeedSortButton(feedSort, viewModel::setFeedSort) }) {
                val list = feeds.valueOrNull
                if (feeds == LoadState.Failed) {
                    LoadFailedHint()
                } else {
                    val rowState = rememberLazyListState()
                    ShowStartOnNewFront(list.orEmpty(), rowState, { it.id }, resetKey = feedSort)
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.keepHorizontalScroll(rowState),
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(list.orEmpty(), key = { it.id }) { feed ->
                            FeedCard(
                                feed,
                                onClick = { onFeedClick(feed.id) },
                                onSettings = { onFeedSettings(feed.id) },
                                modifier = Modifier.width(140.dp).animateItem(),
                                newEpisodes = newEpisodeCounts[feed.id] ?: 0,
                            )
                        }
                    }
                }
            }
        }
        item(key = "add") {
            FilledTonalButton(onClick = onAddPodcast, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.podcast_add), modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/**
 * Image and title of a subscription, at the bottom left of the image the number of downloaded episodes ([FeedDownloadBadge]), at the top right
 * that of the new ones ([newEpisodes]), at the top left the mark of a paused subscription ([FeedPausedBadge]); long press opens the
 * [FeedMenu], whose last entry is the settings ([onSettings]).
 */
@Composable
fun FeedCard(
    feed: PodcastFeed,
    onClick: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
    newEpisodes: Int = 0,
) {
    // Saveable: "Mark all as played" asks inside the menu, and the question would vanish on a rotation (review4 T44).
    var menu by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = stringResource(R.string.action_more),
                onLongClick = { menu = true },
            )
            .padding(contentPadding),
    ) {
        if (menu) FeedMenu(feed, onSettings = onSettings, onDismiss = { menu = false })
        Box {
            PodcastArt(feed.imageUrl, Modifier.fillMaxWidth())
            FeedDownloadBadge(feed.id, Modifier.align(Alignment.BottomStart).padding(6.dp))
            NewEpisodesBadge(newEpisodes, Modifier.align(Alignment.TopEnd).padding(6.dp))
            if (feed.isPaused) FeedPausedBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        // Always two lines tall, so tiles in a row have equal height.
        Text(
            feed.title,
            style = MaterialTheme.typography.bodyMedium,
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private enum class PodcastTab(@StringRes val label: Int) {
    NEW(R.string.podcast_tab_new),
    FEEDS(R.string.podcast_tab_feeds),
    STARRED(R.string.podcast_tab_starred),
    STATS(R.string.podcast_tab_stats),
}

/**
 * Library in "Podcasts" mode: tabs New (the newest episodes of all subscriptions), Subscriptions (grid with floating "+"), Starred
 * and Statistics (with the way to the history in it, [onOpenHistory]); in the header the way to the collected bookmarks ([onOpenBookmarks]).
 */
@Composable
fun PodcastLibraryScreen(
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onAddPodcast: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenBookmarks: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
    starredViewModel: PodcastStarredHistoryViewModel = hiltViewModel(),
    newViewModel: PodcastNewViewModel = hiltViewModel(),
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val latestShown by newViewModel.shown.collectAsStateWithLifecycle()
    val feedSort by viewModel.feedSort.collectAsStateWithLifecycle()
    val newEpisodeCounts by viewModel.newEpisodeCounts.collectAsStateWithLifecycle()
    val starred by starredViewModel.starred.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { PodcastTab.entries.size }
    val scope = rememberCoroutineScope()
    // Multi-selection in the "New" and "Starred" tabs; it ends when the tab is left.
    val selection = rememberEpisodeSelection()
    // Only on a change, not on the first composition: after rotating, the selection stays.
    var selectionPage by rememberSaveable { mutableIntStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectionPage) selection.clear()
        selectionPage = pagerState.currentPage
    }
    val empty: @Composable () -> Unit = {
        EmptyAreaHint(SpokenMode.PODCASTS, stringResource(R.string.podcast_none), stringResource(R.string.podcast_add), onAddPodcast)
    }

    // As in the music library, the sorting of the "Subscriptions" tab is in the app header left of the magnifier, preceded by the bookmarks.
    HeaderAction {
        BookmarksHeaderButton(BookKind.EPISODE, onOpenBookmarks)
        if (PodcastTab.entries[pagerState.targetPage] == PodcastTab.FEEDS) FeedSortButton(feedSort, viewModel::setFeedSort)
    }

    Column(modifier.fillMaxSize()) {
        // During the selection its bar takes the place of the tabs, and the pager stays still.
        if (selection.active) {
            val starredTab = PodcastTab.entries[pagerState.currentPage] == PodcastTab.STARRED
            // "Select all" takes what the tab shows, in "New" after its filter.
            val shown = (if (starredTab) starred else latestShown).valueOrNull.orEmpty()
            val loaded = (if (starredTab) starred else latestShown) is LoadState.Ready
            EpisodeSelectionBar(selection, shown, loaded = loaded, windowInsets = WindowInsets(0))
        } else PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            PodcastTab.entries.forEachIndexed { index, tab ->
                // Four tabs: as in the music library, smaller font instead of wrapping ("Subscriptions").
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                ) { TabLabel(stringResource(tab.label)) }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                // The area pager around it would take the swipe otherwise and switch to books or music, with the selection
                // still active but out of sight (review4 T40).
                .then(if (selection.active) Modifier.pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } } else Modifier),
            userScrollEnabled = !selection.active,
            key = { it },
        ) { page ->
            when (PodcastTab.entries[page]) {
                // Without subscriptions, New and Subscriptions show the same as the start: add and hide.
                PodcastTab.NEW -> if (feeds.valueOrNull?.isEmpty() == true) empty() else NewEpisodes(onEpisodeClick, selection, newViewModel)
                PodcastTab.FEEDS -> FeedGrid(
                    feeds,
                    newEpisodeCounts,
                    onFeedClick,
                    onFeedSettings,
                    onAdd = onAddPodcast,
                    empty = empty,
                    byName = feedSort == FeedSort.NAME,
                    sort = feedSort,
                )
                PodcastTab.STARRED -> if (feeds.valueOrNull?.isEmpty() == true) empty() else StarredEpisodes(onEpisodeClick, selection, starredViewModel)
                PodcastTab.STATS -> PodcastStatsScreen(onFeedClick, onEpisodeClick, onOpenHistory)
            }
        }
    }
}

/**
 * All subscriptions as images, three side by side; the "+" floats at the bottom right and stays in place while scrolling. The fast scroller
 * shows the initial letter when sorted by name ([byName]), otherwise only the handle.
 */
@Composable
private fun FeedGrid(
    state: LoadState<List<PodcastFeed>>,
    newEpisodeCounts: Map<String, Int>,
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onAdd: () -> Unit,
    empty: @Composable () -> Unit,
    byName: Boolean,
    sort: FeedSort,
) {
    Box(Modifier.fillMaxSize()) {
        LoadableList(state, empty) { feeds ->
            val gridState = rememberLazyGridState()
            ScrollToTopEffect(gridState)
            ShowStartOnNewFront(feeds, gridState, { it.id }, resetKey = sort)
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                // Leave space at the bottom so the button doesn't cover the last subscription.
                contentPadding = PaddingValues(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(feeds, key = { it.id }) { feed ->
                    FeedCard(
                        feed,
                        onClick = { onFeedClick(feed.id) },
                        onSettings = { onFeedSettings(feed.id) },
                        modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer).animateItem(),
                        contentPadding = GridTilePadding,
                        newEpisodes = newEpisodeCounts[feed.id] ?: 0,
                    )
                }
            }
            // Ends above the "+", so the handle doesn't disappear beneath it.
            LazyGridFastScroller(
                gridState,
                Modifier.padding(bottom = 88.dp),
                labelAt = if (byName) { index -> foldedLetterOf(feeds.getOrNull(index)?.title.orEmpty().sortKey()) } else null,
            )
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(painterResource(DesignR.drawable.ic_add), contentDescription = stringResource(R.string.podcast_add))
        }
    }
}

/** Entered or stored as `http://`; without a scheme the app adds `https://`. */
internal fun isUnencrypted(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/** Detail page of a podcast: header with image, title and buttons, the episodes below. Info and settings are on their own pages. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    feedId: String,
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onInfo: () -> Unit,
    onSettings: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onSubscribed: () -> Unit = {},
    viewModel: PodcastFeedViewModel = hiltViewModel<PodcastFeedViewModel, PodcastFeedViewModel.Factory> { it.create(feedId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val shownEpisodes by viewModel.shownEpisodes.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }
    val current = feed.valueOrNull
    var switchingSource by rememberSaveable { mutableStateOf(false) }
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    val credentialsUser by viewModel.credentialsUser.collectAsStateWithLifecycle()
    val credentialsAddress by viewModel.credentialsAddress.collectAsStateWithLifecycle()
    val swipe by viewModel.swipeActions.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val areaEffects by viewModel.areaEffects.collectAsStateWithLifecycle()
    val selection = rememberEpisodeSelection()

    // Wide (tablet, from start without a list next to it): not across the full width, otherwise buttons sit far from the title.
    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        if (selection.active) {
            EpisodeSelectionBar(selection, shownEpisodes.valueOrNull.orEmpty(), loaded = shownEpisodes is LoadState.Ready)
        } else TopAppBar(
            title = {},
            navigationIcon = { BackButton(onBack) },
            // The refresh button is at the far right as in the other screens, the search to its left.
            actions = {
                if (current != null) {
                    SortButton(
                        current.listOldestFirst,
                        listOf(false, true),
                        { stringResource(if (it) R.string.episode_sort_oldest else R.string.episode_sort_newest) },
                        viewModel::setListOldestFirst,
                    )
                }
                IconButton(onClick = onSearch, enabled = current != null) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_search))
                }
                IconButton(onClick = viewModel::refresh, enabled = current != null) {
                    Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.podcast_refresh_feed))
                }
                // The same menu as the long press on the subscription (play next, mark all as played, share, …).
                if (current != null) {
                    Box {
                        var menuOpen by rememberSaveable { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                        }
                        if (menuOpen) {
                            FeedMenu(
                                current,
                                onSettings = onSettings,
                                onDismiss = { menuOpen = false },
                                withTitle = false,
                                withRefresh = false,
                                onSubscribe = {
                                    viewModel.subscribe()
                                    onSubscribed()
                                },
                            )
                        }
                    }
                }
            },
        )
        if (current == null) {
            FeedNotReady(feed)
            return@Column
        }
        val list = shownEpisodes.valueOrNull.orEmpty()
        // Header and filter chips stay above the episodes (user, 2026-11-18); the header shrinks while the episodes scroll
        // down (user, 2026-11-21). Compact, it is small enough for landscape too, so there is no scrolling-along fallback.
        val collapse = rememberSaveable(saver = FeedHeaderCollapse.Saver) { FeedHeaderCollapse() }
        val fraction = { collapse.fraction }
        val density = LocalDensity.current
        SideEffect { collapse.fixedRangePx = with(density) { (HEADER_ART_SIZE - HEADER_ART_SIZE_COMPACT).toPx() } }
        val speed = current.effects.speed ?: areaEffects.speed
        // Pull-to-refresh outside the collapse: a downward pull first grows the header, only the rest refreshes.
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.fillMaxSize().nestedScroll(collapse)) {
                // Dragging the header itself shrinks and grows it too (also if it leaves the list hardly any room).
                Column(
                    Modifier.draggable(
                        state = rememberDraggableState { collapse.scrollBy(it) },
                        orientation = Orientation.Vertical,
                        onDragStopped = { collapse.settle() },
                    ),
                ) {
                    FeedHeaderRow(current, fraction, onInfo, onSettings)
                    // Notices shrink away with the header; without any the block is empty, so its range drops back to 0.
                    Column(Modifier.collapsingBlock(fraction, onFullHeight = { collapse.collapsibleRangePx = it.toFloat() })) {
                        if (!current.subscribed) {
                            TrialNotice(
                                text = stringResource(R.string.podcast_trial_notice),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            ) {
                                FilledTonalButton(onClick = {
                                    viewModel.subscribe()
                                    onSubscribed()
                                }) { Text(stringResource(R.string.podcast_add_confirm)) }
                            }
                        }
                        // If syncing has failed for several attempts (concept 12.10), it is shown here with the way to another source.
                        if (current.hasProblem) {
                            TrialNotice(
                                text = feedProblemText(current),
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                                error = true,
                                // Rejected login: more likely credentials than another source.
                                action = when {
                                    // Only the permission is missing: another source would not help (review4 T5).
                                    feedBlockedByHomeNetwork(current) -> {
                                        { FilledTonalButton(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.permission_open_settings)) } }
                                    }
                                    !current.subscribed -> null
                                    current.refreshError == FeedError.DENIED && ardSoundsShowId(current.url) == null -> {
                                        { FilledTonalButton(onClick = { editingCredentials = true }) { Text(stringResource(R.string.podcast_credentials)) } }
                                    }
                                    else -> {
                                        { FilledTonalButton(onClick = { switchingSource = true }) { Text(stringResource(R.string.podcast_source_switch)) } }
                                    }
                                },
                            )
                        }
                    }
                }
                Box(Modifier.padding(top = 12.dp)) {
                    when {
                        episodes == LoadState.Failed -> LoadFailedHint()
                        episodes is LoadState.Ready && episodes.valueOrNull.isNullOrEmpty() -> EmptyHint(stringResource(R.string.podcast_no_episodes))
                        // The chips stay even if the filter leaves nothing, so it can be cleared.
                        episodes is LoadState.Ready -> EpisodeFilterChips(
                            current.episodeFilter,
                            viewModel::setEpisodeFilter,
                            withStarred = episodes.valueOrNull.orEmpty().any { it.book.starred },
                        )
                    }
                }
                // The mini player sees the list's push against its start before the header and pull-to-refresh take it.
                Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(LocalMiniPlayerScrollHide.current)) {
                    if (episodes == LoadState.Loading) LoadingIndicator()
                    val listState = rememberLazyListState()
                    // New episodes from a refresh appear at the top in view instead of above the edge.
                    ShowStartOnNewFront(list, listState, { it.book.id }, resetKey = current?.let { it.episodeFilter to it.listOldestFirst })
                    // Several episodes on the same day: time of day next to the date.
                    val sameDay = remember(list) { episodesSharingDay(list.map { it.book }) }
                    // If the feed names several seasons (`itunes:season`), each gets its header and group.
                    val seasons = remember(list) { seasonRuns(list) { it.book.season } }
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        state = listState,
                        contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                        // Each episode is its own card, as in the "New" tab: long descriptions make one shared surface hard to read.
                        verticalArrangement = Arrangement.spacedBy(CardGap),
                    ) {
                        if (shownEpisodes is LoadState.Ready && list.isEmpty() && episodes.valueOrNull.orEmpty().isNotEmpty()) {
                            item(key = "filter-empty") { EmptyHint(stringResource(R.string.library_filter_empty)) }
                        }
                        val row: @Composable (BookWithProgress) -> Unit = { item ->
                            val id = item.book.id
                            EpisodeSummaryRow(
                                item,
                                withPodcast = false,
                                withTime = id in sameDay,
                                remainingMs = item.remainingPlayMs(speed),
                                download = downloadStates[id] ?: TrackDownload.NONE,
                                downloadFraction = downloadProgress[id],
                                queued = id in queued,
                                playing = id == playingId,
                                selection = selection,
                                onClick = { onEpisodeClick(id) },
                                onPlay = { viewModel.playOrPause(id) },
                                onDownload = { viewModel.download(id) },
                                onRemoveDownload = { viewModel.removeDownload(id) },
                                onToggleQueue = { viewModel.toggleQueued(id) },
                                swipe = swipe,
                            )
                        }
                        if (seasons == null) {
                            cardItems(list, key = { it.book.id }) { row(it) }
                        } else {
                            seasons.forEachIndexed { index, (season, items) ->
                                item(key = "season-$index") { SectionHeader(seasonTitle(season)) }
                                cardItems(items, key = { it.book.id }) { row(it) }
                            }
                        }
                    }
                    // Without a marker: seasons and filter don't turn the index into an episode.
                    LazyListFastScroller(listState)
                }
            }
        }
    }

    if (switchingSource && current != null) {
        val doneText = stringResource(R.string.podcast_source_switch_done)
        SourceSwitchDialog(
            feed = current,
            onDismiss = { switchingSource = false },
            onSwitched = {
                switchingSource = false
                snackbars.show(doneText)
            },
        )
    }
    if (editingCredentials && current != null) {
        FeedCredentialsDialog(
            feedUrl = credentialsAddress ?: current.url,
            rejected = credentialsUser != null,
            initialUsername = credentialsUser.orEmpty(),
            onConfirm = {
                editingCredentials = false
                viewModel.setCredentials(it)
            },
            onDismiss = { editingCredentials = false },
        )
    }
}

private val HEADER_ART_SIZE = 144.dp

/** Cover size of the compact header: two lines of the smaller title still fit next to it. */
private val HEADER_ART_SIZE_COMPACT = 56.dp

/** From this collapse fraction on, the buttons below the title are gone (they would otherwise overlap the shrinking title). */
private const val HEADER_BUTTONS_GONE_AT = 0.25f

/**
 * Image, title, author and buttons of the subscription page, shrinking with [fraction] (0 = full size, 1 = compact, see
 * [FeedHeaderCollapse]): the cover shrinks, the title gets smaller, author and buttons fade out. Reachable again by
 * scrolling back up.
 */
@Composable
private fun FeedHeaderRow(current: PodcastFeed, fraction: () -> Float, onInfo: () -> Unit, onSettings: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Box(Modifier.collapsingSquare(HEADER_ART_SIZE, HEADER_ART_SIZE_COMPACT, fraction)) {
            PodcastArt(current.imageUrl, Modifier.fillMaxSize())
            if (current.isPaused) FeedPausedBadge(Modifier.align(Alignment.TopStart).padding(4.dp))
        }
        // Title and author centered relative to the image height, the buttons flush with the image's bottom edge.
        Box(Modifier.weight(1f).collapsingHeight(HEADER_ART_SIZE, HEADER_ART_SIZE_COMPACT, fraction).padding(start = 16.dp)) {
            Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
                FeedHeaderTitle(current.title, fraction)
                if (current.author.isNotBlank()) {
                    Text(
                        current.author,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.collapsingBlock(fraction, fadeSpeed = 2f),
                    )
                }
            }
            // Only recomposes when the buttons appear or disappear, not on every scroll step.
            val buttonsShown by remember { derivedStateOf { fraction() < HEADER_BUTTONS_GONE_AT } }
            if (buttonsShown) {
                Row(
                    Modifier.align(Alignment.BottomStart).fillMaxWidth()
                        .graphicsLayer { alpha = (1f - fraction() / HEADER_BUTTONS_GONE_AT).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
                        Icon(painterResource(DesignR.drawable.ic_info), contentDescription = stringResource(R.string.podcast_info))
                    }
                    // A trial subscription has no settings; it applies only as long as its episodes are in the queue.
                    if (current.subscribed) {
                        IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                            Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = stringResource(R.string.podcast_settings))
                        }
                    }
                    // If the feed names a page for support (`podcast:funding`), it is shown here.
                    current.fundingUrl?.let { FundingButton(it, current.fundingLabel, 40.dp) }
                }
            }
        }
    }
}

/** Title of [FeedHeaderRow]: its own scope, so only it recomposes while the font size follows the finger. */
@Composable
private fun FeedHeaderTitle(title: String, fraction: () -> Float) {
    val style = lerp(MaterialTheme.typography.titleLarge, MaterialTheme.typography.titleMedium, fraction())
    Text(title, style = style, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Sorting of the subscriptions (start and "Subscriptions" tab share it). */
@Composable
private fun FeedSortButton(sort: FeedSort, onSelect: (FeedSort) -> Unit) {
    SortButton(sort, FeedSort.entries, { stringResource(it.label) }, onSelect)
}

/**
 * At the right of "Recently added": empties the row after confirmation, until newer episodes arrive (concept 12.5). The shelf
 * disappears with its last episode, and with it this button.
 */
@Composable
private fun ClearRecentButton(onClear: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { confirm = true }) {
        Icon(painterResource(DesignR.drawable.ic_clear_all), contentDescription = stringResource(R.string.recent_clear))
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.recent_clear_title)) },
            text = { Text(stringResource(R.string.recent_clear_message)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    confirm = false
                    onClear()
                }) { Text(stringResource(R.string.recent_clear_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private val FeedSort.label: Int
    get() = when (this) {
        FeedSort.NAME -> R.string.sort_name
        FeedSort.NEWEST_EPISODE -> R.string.feed_sort_newest_episode
        FeedSort.RECENTLY_PLAYED -> R.string.sort_recently_played
        FeedSort.MOST_NEW -> R.string.feed_sort_most_new
    }

/**
 * Filter of a subscription's episode list: exactly one is selected. Can be swiped sideways if the width isn't enough.
 * "Starred" is only shown if the subscription has starred episodes ([withStarred]) or it is selected (like the music "Favorites").
 */
@Composable
internal fun EpisodeFilterChips(
    selected: EpisodeFilter,
    onSelect: (EpisodeFilter) -> Unit,
    withStarred: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Row(
        modifier.keepHorizontalScroll(scroll).horizontalScroll(scroll).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        EpisodeFilter.entries.filter { it != EpisodeFilter.STARRED || withStarred || selected == it }.forEach { filter ->
            FilterChip(selected = filter == selected, onClick = { onSelect(filter) }, label = { Text(stringResource(filter.label)) })
        }
    }
}

private val EpisodeFilter.label: Int
    get() = when (this) {
        EpisodeFilter.ALL -> R.string.episode_filter_all
        EpisodeFilter.UNHEARD -> R.string.episode_filter_unheard
        EpisodeFilter.STARTED -> R.string.episode_filter_started
        EpisodeFilter.DOWNLOADED -> R.string.episode_filter_downloaded
        EpisodeFilter.STARRED -> R.string.episode_filter_starred
    }

/** Number of new episodes ([FeedStats.newEpisodes]) at the top right of a subscription's image; from 100 "99+". */
@Composable
private fun NewEpisodesBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    CoverPill(
        if (count > MAX_BADGE_COUNT) stringResource(R.string.count_more_than, MAX_BADGE_COUNT) else count.toString(),
        pluralStringResource(R.plurals.feed_new, count, count),
        modifier,
    )
}

private const val MAX_BADGE_COUNT = 99

/**
 * Mark of a paused subscription ("Keep up to date" off) on its image: a crossed-out sync symbol in a neutral circle, so it
 * doesn't compete with the colored counters for new and downloaded episodes.
 */
@Composable
internal fun FeedPausedBadge(modifier: Modifier = Modifier) {
    val description = stringResource(R.string.podcast_keep_updated_paused_badge)
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.9f),
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Icon(
            painterResource(DesignR.drawable.ic_sync_disabled),
            contentDescription = null,
            modifier = Modifier.padding(3.dp).size(14.dp),
        )
    }
}

/** Content of the podcast pages while the podcast isn't loaded or no longer exists. */
@Composable
private fun FeedNotReady(state: LoadState<PodcastFeed>) {
    when (state) {
        LoadState.Loading -> LoadingIndicator()
        LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
        LoadState.Failed -> LoadFailedHint()
        is LoadState.Ready -> Unit
    }
}

/** Search within a podcast's episodes: title and summary, with the hint "Search in …" below the field. */
@Composable
fun PodcastSearchScreen(
    feedId: String,
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastFeedViewModel = hiltViewModel<PodcastFeedViewModel, PodcastFeedViewModel.Factory> { it.create(feedId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val result by viewModel.searchHits.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Only on first open, as in the global search: coming back from a hit, the keyboard would cover half the hits (review4 T42).
    LaunchedEffect(Unit) { if (query.isEmpty()) focus.requestFocus() }
    LaunchedEffect(query) { viewModel.search(query) }
    val swipe by viewModel.swipeActions.collectAsStateWithLifecycle()
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val areaEffects by viewModel.areaEffects.collectAsStateWithLifecycle()
    val selection = rememberEpisodeSelection()

    val trimmed = query.trim()
    // While typing, the last results stay until the new ones are there; "Nothing found" only for the current text.
    val hits = result?.hits.orEmpty()
    val settled = result?.query == trimmed

    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        // During the selection the bar replaces the search field; the results stay, and so does the search text.
        if (selection.active) EpisodeSelectionBar(selection, hits, loaded = result != null) else Row(Modifier.statusBarsPadding().padding(end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            BackButton(onBack)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // The hits follow the typing; the search key only closes the keyboard, as in the global search.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }
        feed.valueOrNull?.let {
            Text(
                stringResource(R.string.podcast_search_in, it.title),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 68.dp, end = 16.dp, top = 2.dp, bottom = 4.dp),
            )
        }
        val sameDayHits = remember(hits) { episodesSharingDay(hits.map { it.book }) }
        when {
            trimmed.isEmpty() -> Unit
            hits.isEmpty() -> if (settled) EmptyHint(stringResource(R.string.search_no_results, trimmed))
            // Each hit its own card, as on the subscription page.
            else -> FastScrollLazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(CardGap),
            ) {
                val sameDay = sameDayHits
                val speed = feed.valueOrNull?.effects?.speed ?: areaEffects.speed
                cardItems(hits, key = { it.book.id }) { item ->
                    val id = item.book.id
                    EpisodeSummaryRow(
                        item,
                        withPodcast = false,
                        withTime = id in sameDay,
                        remainingMs = item.remainingPlayMs(speed),
                        download = downloadStates[id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[id],
                        queued = id in queued,
                        playing = id == playingId,
                        selection = selection,
                        onClick = { onEpisodeClick(id) },
                        onPlay = { viewModel.playOrPause(id) },
                        onDownload = { viewModel.download(id) },
                        onRemoveDownload = { viewModel.removeDownload(id) },
                        onToggleQueue = { viewModel.toggleQueued(id) },
                        swipe = swipe,
                    )
                }
            }
        }
    }
}

/** Info page: the full description and the feed address (a tap copies it to the clipboard). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastInfoScreen(
    feedId: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastFeedViewModel = hiltViewModel<PodcastFeedViewModel, PodcastFeedViewModel.Factory> { it.create(feedId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val current = feed.valueOrNull
    val clipboard = LocalClipboard.current
    val snackbars = LocalSnackbars.current
    val copiedText = stringResource(R.string.podcast_url_copied)
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.podcast_info), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        if (current == null) {
            FeedNotReady(feed)
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(current.title, style = MaterialTheme.typography.titleLarge)
            if (current.author.isNotBlank()) {
                Text(current.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val formatted = rememberDescription(current.description)
            Text(
                if (current.description.isBlank()) AnnotatedString(stringResource(R.string.podcast_no_description)) else formatted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!current.isLocal) {
                // Shows from ARD Sounds have no feed: their page link is shown, which can also be subscribed to again.
                val ardShow = ardSoundsShowId(current.url)
                val shownUrl = ardShow?.let(::ardSoundsPageUrl) ?: current.url
                Text(
                    stringResource(if (ardShow != null) R.string.podcast_ard_sounds_page else R.string.podcast_feed_url),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 16.dp),
                )
                Text(
                    shownUrl,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(current.title, shownUrl))) }
                        snackbars.show(copiedText)
                    },
                )
                // Also for subscriptions imported via OPML or database that never went through the subscription dialog.
                if (isUnencrypted(current.url)) {
                    Text(
                        stringResource(R.string.podcast_feed_unencrypted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** Row like [SwitchRow], but with a tap instead of a switch. */
@Composable
private fun ActionRow(
    title: String,
    summary: String,
    icon: Int,
    onClick: () -> Unit,
    titleColor: Color = Color.Unspecified,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(summary) },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        colors = if (titleColor == Color.Unspecified) ListItemDefaults.colors() else ListItemDefaults.colors(headlineColor = titleColor),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Settings of a podcast: downloads, "Continue listening", playback and the subscription (notification, source, unsubscribe). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastSettingsScreen(
    feedId: String,
    onBack: () -> Unit,
    onUnsubscribed: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastFeedViewModel = hiltViewModel<PodcastFeedViewModel, PodcastFeedViewModel.Factory> { it.create(feedId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val areaEffects by viewModel.areaEffects.collectAsStateWithLifecycle()
    val credentialsUser by viewModel.credentialsUser.collectAsStateWithLifecycle()
    val credentialsAddress by viewModel.credentialsAddress.collectAsStateWithLifecycle()
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    var switchingSource by rememberSaveable { mutableStateOf(false) }
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    val current = feed.valueOrNull
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }

    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            // Below it the podcast's name: the page is also reached from the long-press menu, without its page before it.
            title = {
                Column {
                    Text(stringResource(R.string.podcast_settings), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    current?.let {
                        Text(
                            it.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            },
            navigationIcon = { BackButton(onBack) },
        )
        if (current == null) {
            FeedNotReady(feed)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            // Podcasts from the device have neither feed nor downloads and can only be ended by removing the files;
            // a section without a visible row is omitted.
            group("podcast-downloads", R.string.nav_downloads) {
                if (!current.isLocal) row("auto-download") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_auto_download),
                        summary = stringResource(R.string.podcast_auto_download_summary),
                        icon = DesignR.drawable.ic_download,
                        checked = current.autoDownload,
                        onCheckedChange = viewModel::setAutoDownload,
                    )
                }
                if (!current.isLocal && current.autoDownload && current.queueMode != FeedQueueMode.NEWEST_ONLY) row("auto-count") {
                    val defaultCount by viewModel.defaultAutoDownloadCount.collectAsStateWithLifecycle()
                    FeedAutoCountRow(current, defaultCount, viewModel::setAutoDownloadCount)
                }
                if (downloadStates.values.any { it == TrackDownload.DONE }) {
                    row("remove-heard") {
                        var confirm by rememberSaveable { mutableStateOf(false) }
                        ActionRow(
                            title = stringResource(R.string.podcast_remove_heard_downloads),
                            summary = stringResource(R.string.podcast_remove_heard_downloads_summary),
                            icon = DesignR.drawable.ic_delete,
                            onClick = { confirm = true },
                        )
                        if (confirm) {
                            RemoveHeardDownloadsDialog(
                                onConfirm = {
                                    confirm = false
                                    viewModel.removeHeardDownloads()
                                },
                                onDismiss = { confirm = false },
                            )
                        }
                    }
                }
            }
            group("podcast-queue", R.string.spoken_continue) {
                row("continuous") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_continuous),
                        summary = stringResource(R.string.podcast_continuous_summary),
                        icon = DesignR.drawable.ic_playlist_play,
                        checked = current.continuous,
                        onCheckedChange = viewModel::setContinuous,
                    )
                }
                row("newest-first") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_newest_first),
                        summary = stringResource(R.string.podcast_newest_first_summary),
                        icon = DesignR.drawable.ic_list,
                        checked = current.newestFirst,
                        onCheckedChange = viewModel::setNewestFirst,
                    )
                }
                row("queue") { FeedQueueRows(current, viewModel::setQueueMode, viewModel::setKeepEpisodes) }
            }
            group("podcast-playback", R.string.settings_playback) {
                row("effects") {
                    FeedPlaybackRows(current, areaEffects, viewModel::setEffects, viewModel::setSkipIntroOutro)
                }
            }
            group("podcast-subscription", R.string.podcast_section_subscription) {
                if (!current.isLocal) row("keep-updated") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_keep_updated),
                        summary = stringResource(R.string.podcast_keep_updated_summary),
                        icon = DesignR.drawable.ic_sync,
                        checked = current.keepUpdated,
                        onCheckedChange = viewModel::setKeepUpdated,
                    )
                }
                if (!current.isLocal) row("notify-new") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_notify_new),
                        summary = stringResource(R.string.podcast_notify_new_summary),
                        icon = DesignR.drawable.ic_notifications,
                        checked = current.notifyNew,
                        onCheckedChange = viewModel::setNotifyNew,
                    )
                }
                // Protected feeds (concept 12.10); ARD Sounds has no login.
                if (!current.isLocal && ardSoundsShowId(current.url) == null) row("credentials") {
                    ActionRow(
                        title = stringResource(R.string.podcast_credentials),
                        summary = credentialsUser?.let { stringResource(R.string.podcast_credentials_user, it) }
                            ?: stringResource(R.string.podcast_credentials_none),
                        icon = DesignR.drawable.ic_person,
                        onClick = { editingCredentials = true },
                    )
                }
                if (!current.isLocal) row("switch-source") {
                    ActionRow(
                        title = stringResource(R.string.podcast_source_switch),
                        summary = stringResource(R.string.podcast_source_switch_summary),
                        icon = DesignR.drawable.ic_sync,
                        onClick = { switchingSource = true },
                    )
                }
                if (!current.isLocal) row("unsubscribe") {
                    ActionRow(
                        title = stringResource(R.string.podcast_unsubscribe),
                        summary = stringResource(R.string.podcast_unsubscribe_summary),
                        icon = DesignR.drawable.ic_close,
                        onClick = { confirmUnsubscribe = true },
                        titleColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }

    if (editingCredentials && current != null) {
        FeedCredentialsDialog(
            feedUrl = credentialsAddress ?: current.url,
            rejected = credentialsUser != null && current.refreshError == FeedError.DENIED,
            initialUsername = credentialsUser.orEmpty(),
            onConfirm = {
                editingCredentials = false
                viewModel.setCredentials(it)
            },
            onDismiss = { editingCredentials = false },
            onRemove = credentialsUser?.let {
                {
                    editingCredentials = false
                    viewModel.setCredentials(null)
                }
            },
        )
    }

    if (switchingSource && current != null) {
        val doneText = stringResource(R.string.podcast_source_switch_done)
        SourceSwitchDialog(
            feed = current,
            onDismiss = { switchingSource = false },
            onSwitched = {
                switchingSource = false
                snackbars.show(doneText)
            },
        )
    }

    if (confirmUnsubscribe && current != null) {
        val archivedText = stringResource(R.string.podcast_archived_done, current.title)
        // Archive on the left, unsubscribe at the far right with "Cancel" in between: deleting shouldn't be hit by accident.
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(stringResource(R.string.podcast_unsubscribe_title)) },
            text = { Text(stringResource(R.string.podcast_unsubscribe_message, current.title)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.unsubscribe()
                    onUnsubscribed()
                }) { Text(stringResource(R.string.podcast_unsubscribe)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        confirmUnsubscribe = false
                        viewModel.archive()
                        snackbars.show(archivedText, SnackbarDuration.Long)
                        onUnsubscribed()
                    }) { Text(stringResource(R.string.podcast_archive)) }
                    TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            },
        )
    }
}

/**
 * Download; while downloading a circle that fills up (empty = waiting, a tap cancels), afterwards a check mark (a tap deletes).
 * If the size is unknown, the circle spins. Check mark and error in the marker colors ([OfflineMarkIcon]). Inactive ([enabled]
 * `false`) during a multi-selection, where a tap on the check mark would otherwise delete the download instead of selecting the row.
 */
@Composable
internal fun DownloadButton(
    state: TrackDownload,
    fraction: Float?,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
    enabled: Boolean = true,
) {
    val disabledTint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    when (state) {
        TrackDownload.QUEUED, TrackDownload.RUNNING -> {
            val cancel = stringResource(R.string.podcast_download_cancel)
            IconButton(onClick = onRemove, enabled = enabled, modifier = Modifier.semantics { contentDescription = cancel }) {
                if (fraction != null && fraction == EpisodeDownloadProgress.UNKNOWN) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { fraction ?: 0f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        TrackDownload.DONE -> IconButton(onClick = onRemove, enabled = enabled) {
            Icon(
                painterResource(DesignR.drawable.ic_download_done),
                contentDescription = stringResource(R.string.podcast_download_remove),
                tint = if (enabled) MaterialTheme.colorScheme.primary else disabledTint,
            )
        }
        TrackDownload.FAILED -> IconButton(onClick = onDownload, enabled = enabled) {
            Icon(
                painterResource(DesignR.drawable.ic_error),
                contentDescription = stringResource(R.string.podcast_download_retry),
                tint = if (enabled) MaterialTheme.colorScheme.error else disabledTint,
            )
        }
        TrackDownload.NONE, TrackDownload.REMOVED -> IconButton(onClick = onDownload, enabled = enabled) {
            Icon(painterResource(DesignR.drawable.ic_download), contentDescription = stringResource(R.string.podcast_download_episode))
        }
    }
}

/** Square podcast image from the feed; until it is loaded (or without an image) the icon stays visible. */
@Composable
fun PodcastArt(
    imageUrl: String?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .border(CoverOutlineWidth, coverOutlineColor(), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(DesignR.drawable.ic_playlist_play),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(0.4f),
        )
        if (imageUrl != null) {
            AsyncImage(
                model = imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
