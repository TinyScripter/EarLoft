package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.ui.platform.LocalResources
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
import de.kleinabersmart.earloft.ui.components.HeaderAction
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.semantics.selected as selectedSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.FeedEpisodes
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
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.Shelf
import de.kleinabersmart.earloft.ui.components.coverOutlineColor
import de.kleinabersmart.earloft.ui.components.gridTile
import de.kleinabersmart.earloft.ui.components.ShelfInset
import de.kleinabersmart.earloft.ui.components.keepHorizontalScroll
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.ui.components.TwoColumnMinWidth
import de.kleinabersmart.earloft.ui.valueOrNull
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.ui.library.TabLabel
import de.kleinabersmart.earloft.ui.settings.SwitchRow
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Start im Modus „Podcasts“: Weiterhören (angefangene Folgen, die zuletzt gehörte links, neue am Ende), zuletzt
 * hinzugefügte Folgen und die Abos.
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
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
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
                    LazyRow(
                        state = continueRow.state,
                        modifier = Modifier.keepHorizontalScroll(continueRow.state),
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val sameDay = episodesSharingDay(continueRow.items.map { it.book })
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
                Shelf(stringResource(R.string.spoken_recent)) {
                    BookRow(recentRow.items, onEpisodeClick, state = recentRow.state, playingId = playingId, onPlay = viewModel::playOrPause)
                }
            }
        }
        // Ohne Abos: an erster Stelle „Podcast hinzufügen“, darunter der Weg zum Ausblenden des Bereichs.
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
                    ShowStartOnNewFirst(list?.firstOrNull()?.id) { rowState.scrollToItem(0) }
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
 * Bild und Titel eines Abos, unten links auf dem Bild die Zahl der geladenen Folgen ([FeedDownloadBadge]), oben rechts die
 * der neuen ([newEpisodes]); Langklick öffnet das [FeedMenu], dessen letzter Eintrag die Einstellungen ([onSettings]).
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
    var menu by remember { mutableStateOf(false) }
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
        }
        // Immer zwei Zeilen hoch, damit Kacheln einer Reihe gleich hoch sind.
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
    EPISODES(R.string.podcast_episodes),
    STARRED(R.string.podcast_tab_starred),
    FEEDS(R.string.podcast_tab_feeds),
    STATS(R.string.podcast_tab_stats),
}

/**
 * Bibliothek im Modus „Podcasts“: Reiter Folgen (ungehörte je Abo), Gemerkt, Abos (Raster mit schwebendem „+“) und
 * Statistik (darin der Weg zum Verlauf, [onOpenHistory]); im Kopf der Weg zu den gesammelten Lesezeichen ([onOpenBookmarks]).
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
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val unheard by viewModel.unheard.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val feedSort by viewModel.feedSort.collectAsStateWithLifecycle()
    val newEpisodeCounts by viewModel.newEpisodeCounts.collectAsStateWithLifecycle()
    val starred by starredViewModel.starred.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { PodcastTab.entries.size }
    val scope = rememberCoroutineScope()
    // Mehrfachauswahl in den Reitern „Folgen“ und „Gemerkt“; sie endet, wenn man den Reiter verlässt.
    val selection = rememberEpisodeSelection()
    // Nur bei einem Wechsel, nicht beim ersten Aufbau: Nach dem Drehen bleibt die Auswahl.
    var selectionPage by rememberSaveable { mutableIntStateOf(pagerState.currentPage) }
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != selectionPage) selection.clear()
        selectionPage = pagerState.currentPage
    }
    val empty: @Composable () -> Unit = {
        EmptyAreaHint(SpokenMode.PODCASTS, stringResource(R.string.podcast_none), stringResource(R.string.podcast_add), onAddPodcast)
    }

    // Wie in der Musik-Bibliothek steht die Sortierung des Reiters „Abos“ im Kopf der App links von der Lupe, davor die Lesezeichen.
    HeaderAction {
        BookmarksHeaderButton(BookKind.EPISODE, onOpenBookmarks)
        if (PodcastTab.entries[pagerState.targetPage] == PodcastTab.FEEDS) FeedSortButton(feedSort, viewModel::setFeedSort)
    }

    Column(modifier.fillMaxSize()) {
        // Während der Auswahl steht ihre Leiste an der Stelle der Reiter, und der Pager bleibt stehen.
        if (selection.active) {
            val starredTab = PodcastTab.entries[pagerState.currentPage] == PodcastTab.STARRED
            val shown = if (starredTab) starred.valueOrNull.orEmpty() else unheard.valueOrNull.orEmpty().flatMap { it.episodes }
            val loaded = (if (starredTab) starred is LoadState.Ready else unheard is LoadState.Ready)
            EpisodeSelectionBar(selection, shown, loaded = loaded, windowInsets = WindowInsets(0))
        } else PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            PodcastTab.entries.forEachIndexed { index, tab ->
                // Vier Reiter: wie in der Musik-Bibliothek kleinere Schrift statt Umbruch („Subscriptions“).
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                ) { TabLabel(stringResource(tab.label)) }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            userScrollEnabled = !selection.active,
            key = { it },
        ) { page ->
            when (PodcastTab.entries[page]) {
                // Ohne Abos zeigen Folgen und Abos dasselbe wie der Start: Hinzufügen und Ausblenden.
                PodcastTab.EPISODES -> if (feeds.valueOrNull?.isEmpty() == true) empty() else UnheardEpisodes(
                    unheard,
                    hasFeeds = feeds.valueOrNull?.isNotEmpty() == true,
                    onFeedClick,
                    onEpisodeClick,
                    playingId,
                    viewModel::playOrPause,
                    selection,
                )
                PodcastTab.STARRED -> if (feeds.valueOrNull?.isEmpty() == true) empty() else StarredEpisodes(onEpisodeClick, selection, starredViewModel)
                PodcastTab.FEEDS -> FeedGrid(feeds, newEpisodeCounts, onFeedClick, onFeedSettings, onAdd = onAddPodcast, empty = empty)
                PodcastTab.STATS -> PodcastStatsScreen(onFeedClick, onEpisodeClick, onOpenHistory)
            }
        }
    }
}

/** Je Abo (untereinander) die ungehörten Folgen, horizontal scrollbar. */
@Composable
private fun UnheardEpisodes(
    state: LoadState<List<FeedEpisodes>>,
    hasFeeds: Boolean,
    onFeedClick: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    playingId: Long?,
    onPlay: (Long) -> Unit,
    selection: EpisodeSelection,
) {
    LoadableList(state, stringResource(if (hasFeeds) R.string.podcast_all_heard else R.string.podcast_none)) { unheard ->
        UnheardList(unheard, onFeedClick, onEpisodeClick, playingId, onPlay, selection)
    }
}

@Composable
private fun UnheardList(
    unheard: List<FeedEpisodes>,
    onFeedClick: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    playingId: Long?,
    onPlay: (Long) -> Unit,
    selection: EpisodeSelection,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
        unheard.forEach { (feed, episodes) ->
            item(key = "feed-${feed.id}") {
                Shelf(feed.title, onTitleClick = { onFeedClick(feed.id) }) {
                    val rowState = rememberLazyListState()
                    LazyRow(
                        state = rowState,
                        modifier = Modifier.keepHorizontalScroll(rowState),
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val sameDay = episodesSharingDay(episodes.map { it.book })
                        items(episodes, key = { it.book.id }) { item ->
                            BookCard(
                                item,
                                onClick = { onEpisodeClick(item.book.id) },
                                modifier = Modifier.width(140.dp),
                                onPlay = { onPlay(item.book.id) },
                                playing = item.book.id == playingId,
                                withTime = item.book.id in sameDay,
                                selection = selection,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Alle Abos als Bilder, drei nebeneinander; das „+“ schwebt unten rechts und bleibt beim Scrollen stehen. */
@Composable
private fun FeedGrid(
    state: LoadState<List<PodcastFeed>>,
    newEpisodeCounts: Map<String, Int>,
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onAdd: () -> Unit,
    empty: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LoadableList(state, empty) { feeds ->
            val gridState = rememberLazyGridState()
            ShowStartOnNewFirst(feeds.firstOrNull()?.id) { gridState.scrollToItem(0) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                // Unten Platz lassen, damit der Knopf das letzte Abo nicht verdeckt.
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
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(painterResource(DesignR.drawable.ic_add), contentDescription = stringResource(R.string.podcast_add))
        }
    }
}

/** Eingegeben oder gespeichert als `http://`; ohne Schema ergänzt die App `https://`. */
internal fun isUnencrypted(url: String): Boolean = url.trim().startsWith("http://", ignoreCase = true)

/** Detailseite eines Podcasts: Kopf mit Bild, Titel und Knöpfen, darunter die Folgen. Info und Einstellungen liegen auf eigenen Seiten. */
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
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }
    val current = feed.valueOrNull
    var switchingSource by rememberSaveable { mutableStateOf(false) }
    var editingCredentials by rememberSaveable { mutableStateOf(false) }
    val credentialsUser by viewModel.credentialsUser.collectAsStateWithLifecycle()
    val credentialsAddress by viewModel.credentialsAddress.collectAsStateWithLifecycle()
    val swipe by viewModel.swipeActions.collectAsStateWithLifecycle()
    val selection = rememberEpisodeSelection()

    // Breit (Tablet, von Start aus ohne Liste daneben): nicht über die ganze Breite, sonst stehen Knöpfe weit vom Titel.
    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        if (selection.active) {
            EpisodeSelectionBar(selection, shownEpisodes.valueOrNull.orEmpty(), loaded = shownEpisodes is LoadState.Ready)
        } else TopAppBar(
            title = {},
            navigationIcon = { BackButton(onBack) },
            // Der Aktualisieren-Knopf steht ganz rechts wie in den anderen Bildschirmen, die Suche links davon.
            actions = {
                if (current != null) {
                    SortButton(
                        current.listOldestFirst,
                        listOf(false, true),
                        { stringResource(if (it) R.string.episode_sort_oldest else R.string.episode_sort_newest) },
                        viewModel::setListOldestFirst,
                    )
                }
                if (current != null && !current.isLocal) ShareButton(ShareTarget.Feed(current.id))
                IconButton(onClick = onSearch, enabled = current != null) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_search))
                }
                IconButton(onClick = viewModel::refresh, enabled = current != null) {
                    Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.podcast_refresh_feed))
                }
            },
        )
        if (current == null) {
            FeedNotReady(feed)
            return@Column
        }
        val list = shownEpisodes.valueOrNull.orEmpty()
        PullToRefreshBox(isRefreshing = refreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                item(key = "header") {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                        PodcastArt(current.imageUrl, Modifier.size(HEADER_ART_SIZE))
                        // Titel und Autor mittig zur Bildhöhe, die Knöpfe bündig mit der Unterkante des Bildes.
                        Box(Modifier.weight(1f).height(HEADER_ART_SIZE).padding(start = 16.dp)) {
                            Column(Modifier.align(Alignment.Center).fillMaxWidth()) {
                                Text(current.title, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (current.author.isNotBlank()) {
                                    Text(
                                        current.author,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Row(Modifier.align(Alignment.BottomStart).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = onInfo, modifier = Modifier.size(40.dp)) {
                                    Icon(painterResource(DesignR.drawable.ic_info), contentDescription = stringResource(R.string.podcast_info))
                                }
                                // Ein Probeabo hat keine Einstellungen; es gilt nur, solange seine Folgen in der Warteschlange stehen.
                                if (current.subscribed) {
                                    IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                                        Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = stringResource(R.string.podcast_settings))
                                    }
                                }
                                // Nennt der Feed eine Seite zum Unterstützen (`podcast:funding`), steht sie hier.
                                current.fundingUrl?.let { FundingButton(it, current.fundingLabel, 40.dp) }
                            }
                        }
                    }
                }
                if (!current.subscribed) {
                    item(key = "trial") {
                        TrialNotice(
                            text = stringResource(R.string.podcast_trial_notice),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                        ) {
                            FilledTonalButton(onClick = viewModel::subscribe) { Text(stringResource(R.string.podcast_add_confirm)) }
                        }
                    }
                }
                // Scheitert der Abgleich seit mehreren Versuchen (Konzept 12.10), steht es hier mit dem Weg zu einer anderen Quelle.
                if (current.hasProblem) {
                    item(key = "problem") {
                        TrialNotice(
                            text = feedProblemText(current),
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
                            error = true,
                            // Abgelehnte Anmeldung: eher Zugangsdaten als eine andere Quelle.
                            action = when {
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
                item(key = "episodes-header") { SectionHeader(stringResource(R.string.podcast_episodes)) }
                if (episodes == LoadState.Failed) {
                    item(key = "failed") { LoadFailedHint() }
                } else if (episodes is LoadState.Ready && episodes.valueOrNull.isNullOrEmpty()) {
                    item(key = "empty") { EmptyHint(stringResource(R.string.podcast_no_episodes)) }
                } else if (episodes is LoadState.Ready) {
                    // Die Chips bleiben stehen, auch wenn der Filter nichts übrig lässt, damit man ihn lösen kann.
                    item(key = "filter") {
                        EpisodeFilterChips(
                            current.episodeFilter,
                            viewModel::setEpisodeFilter,
                            withStarred = episodes.valueOrNull.orEmpty().any { it.book.starred },
                        )
                    }
                    if (shownEpisodes is LoadState.Ready && list.isEmpty()) {
                        item(key = "filter-empty") { EmptyHint(stringResource(R.string.library_filter_empty)) }
                    }
                }
                // Mehrere Folgen am selben Tag bzw. mit gleichem Titel: Uhrzeit bzw. erste Zeile der Shownotes dazu.
                val sameDay = episodesSharingDay(list.map { it.book })
                val sameTitle = episodesSharingTitle(list.map { it.book })
                val row: @Composable (BookWithProgress) -> Unit = { item ->
                    EpisodeRow(
                        item,
                        withTime = item.book.id in sameDay,
                        withNotes = item.book.id in sameTitle,
                        download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[item.book.id],
                        playing = item.book.id == playingId,
                        onClick = { onEpisodeClick(item.book.id) },
                        onPlay = { viewModel.playOrPause(item.book.id) },
                        onDownload = { viewModel.download(item.book.id) },
                        onRemoveDownload = { viewModel.removeDownload(item.book.id) },
                        selection = selection,
                        swipe = swipe,
                    )
                }
                // Nennt der Feed mehrere Staffeln (`itunes:season`), bekommt jede ihre Kopfzeile und Gruppe.
                val seasons = seasonRuns(list) { it.book.season }
                if (seasons == null) {
                    groupedItems(list, key = { it.book.id }) { row(it) }
                } else {
                    seasons.forEachIndexed { index, (season, items) ->
                        item(key = "season-$index") { SectionHeader(seasonTitle(season)) }
                        groupedItems(items, key = { it.book.id }) { row(it) }
                    }
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

/**
 * Steht ein anderes Abo vorn (andere Sortierung, neue Folge, gehört), zeigt die Liste wieder ihren Anfang: Eine Lazy-Liste
 * hielte sonst das bisher erste Abo fest, und die neuen ersten lägen außerhalb (wie [rememberFrontRow]).
 */
@Composable
private fun ShowStartOnNewFirst(firstId: String?, scrollToStart: suspend () -> Unit) {
    var shown by rememberSaveable { mutableStateOf(firstId) }
    LaunchedEffect(firstId) {
        if (firstId != shown) {
            shown = firstId
            scrollToStart()
        }
    }
}

/** Sortierung der Abos (Start und Reiter „Abos“ teilen sie). */
@Composable
private fun FeedSortButton(sort: FeedSort, onSelect: (FeedSort) -> Unit) {
    SortButton(sort, FeedSort.entries, { stringResource(it.label) }, onSelect)
}

private val FeedSort.label: Int
    get() = when (this) {
        FeedSort.NAME -> R.string.sort_name
        FeedSort.NEWEST_EPISODE -> R.string.feed_sort_newest_episode
        FeedSort.RECENTLY_PLAYED -> R.string.sort_recently_played
        FeedSort.MOST_NEW -> R.string.feed_sort_most_new
    }

/**
 * Filter der Folgenliste eines Abos: genau einer ist gewählt. Lässt sich seitlich wischen, wenn die Breite nicht reicht.
 * „Gemerkt“ steht nur da, wenn das Abo gemerkte Folgen hat ([withStarred]) oder er gewählt ist (wie „Favoriten“ der Musik).
 */
@Composable
private fun EpisodeFilterChips(selected: EpisodeFilter, onSelect: (EpisodeFilter) -> Unit, withStarred: Boolean) {
    val scroll = rememberScrollState()
    Row(
        Modifier.keepHorizontalScroll(scroll).horizontalScroll(scroll).padding(horizontal = 16.dp),
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

/** Zahl der neuen Folgen ([FeedStats.newEpisodes]) oben rechts auf dem Bild eines Abos; ab 100 „99+“. */
@Composable
private fun NewEpisodesBadge(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    CoverPill(
        if (count > MAX_BADGE_COUNT) "$MAX_BADGE_COUNT+" else count.toString(),
        pluralStringResource(R.plurals.feed_new, count, count),
        modifier,
    )
}

private const val MAX_BADGE_COUNT = 99

/** Inhalt der Podcast-Seiten, solange der Podcast nicht geladen ist oder nicht mehr existiert. */
@Composable
private fun FeedNotReady(state: LoadState<PodcastFeed>) {
    when (state) {
        LoadState.Loading -> LoadingIndicator()
        LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
        LoadState.Failed -> LoadFailedHint()
        is LoadState.Ready -> Unit
    }
}

/** Suche in den Folgen eines Podcasts: Überschrift und Inhaltsangabe, mit Hinweis „In … suchen“ unter dem Feld. */
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
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) { viewModel.search(query) }
    val swipe by viewModel.swipeActions.collectAsStateWithLifecycle()
    val selection = rememberEpisodeSelection()

    val trimmed = query.trim()
    // Beim Tippen bleiben die letzten Treffer stehen, bis die neuen da sind; „Nichts gefunden“ erst für den aktuellen Text.
    val hits = result?.hits.orEmpty()
    val settled = result?.query == trimmed

    Column(modifier.fillMaxSize().readableWidth(TwoColumnMinWidth)) {
        // Während der Auswahl ersetzt die Leiste das Suchfeld; die Treffer bleiben, der Suchtext auch.
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
        when {
            trimmed.isEmpty() -> Unit
            hits.isEmpty() -> if (settled) EmptyHint(stringResource(R.string.search_no_results, trimmed))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
                val sameDay = episodesSharingDay(hits.map { it.book })
                val sameTitle = episodesSharingTitle(hits.map { it.book })
                groupedItems(hits, key = { it.book.id }) { item ->
                    EpisodeRow(
                        item,
                        withTime = item.book.id in sameDay,
                        withNotes = item.book.id in sameTitle,
                        download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[item.book.id],
                        playing = item.book.id == playingId,
                        onClick = { onEpisodeClick(item.book.id) },
                        onPlay = { viewModel.playOrPause(item.book.id) },
                        onDownload = { viewModel.download(item.book.id) },
                        onRemoveDownload = { viewModel.removeDownload(item.book.id) },
                        selection = selection,
                        swipe = swipe,
                    )
                }
            }
        }
    }
}

/** Info-Seite: die komplette Beschreibung und die Feed-Adresse (Tipp kopiert sie in die Zwischenablage). */
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
                // Sendungen aus ARD Sounds haben keinen Feed: Gezeigt wird der Link ihrer Seite, der sich auch wieder abonnieren lässt.
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
                // Auch für per OPML oder Datenbank übernommene Abos, die nie durch den Abo-Dialog gingen.
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

/** Zeile wie [SwitchRow], aber mit Tipp statt Schalter. */
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

/** Einstellungen eines Podcasts: Downloads, „Weiterhören“, Wiedergabe und das Abo (Benachrichtigung, Quelle, beenden). */
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
            // Darunter der Name des Podcasts: Die Seite kommt auch aus dem Langklick-Menü, ohne seine Seite davor.
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
            // Podcasts vom Gerät haben weder Feed noch Downloads und lassen sich nur durch Entfernen der Dateien beenden;
            // ein Abschnitt ohne sichtbare Zeile entfällt.
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
                        ActionRow(
                            title = stringResource(R.string.podcast_remove_heard_downloads),
                            summary = stringResource(R.string.podcast_remove_heard_downloads_summary),
                            icon = DesignR.drawable.ic_delete,
                            onClick = viewModel::removeHeardDownloads,
                        )
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
                if (!current.isLocal) row("notify-new") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_notify_new),
                        summary = stringResource(R.string.podcast_notify_new_summary),
                        icon = DesignR.drawable.ic_notifications,
                        checked = current.notifyNew,
                        onCheckedChange = viewModel::setNotifyNew,
                    )
                }
                // Geschützte Feeds (Konzept 12.10); ARD Sounds kennt keine Anmeldung.
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
        // Archivieren links, Abo beenden ganz rechts mit „Abbrechen“ dazwischen: Das Löschen soll man nicht versehentlich treffen.
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(stringResource(R.string.podcast_unsubscribe_title)) },
            text = { Text(stringResource(R.string.podcast_unsubscribe_message, current.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.unsubscribe()
                    onUnsubscribed()
                }) { Text(stringResource(R.string.podcast_unsubscribe), color = MaterialTheme.colorScheme.error) }
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
 * Herunterladen; beim Laden ein Kreis, der sich füllt (leer = wartet, Tipp bricht ab), danach Haken (Tipp löscht).
 * Ist die Größe unbekannt, dreht sich der Kreis. Haken und Fehler in den Farben der Markierung ([OfflineMarkIcon]).
 */
@Composable
private fun DownloadButton(state: TrackDownload, fraction: Float?, onDownload: () -> Unit, onRemove: () -> Unit) {
    when (state) {
        TrackDownload.QUEUED, TrackDownload.RUNNING -> {
            val cancel = stringResource(R.string.podcast_download_cancel)
            IconButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = cancel }) {
                if (fraction != null && fraction == EpisodeDownloadProgress.UNKNOWN) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(progress = { fraction ?: 0f }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
        }
        TrackDownload.DONE -> IconButton(onClick = onRemove) {
            Icon(
                painterResource(DesignR.drawable.ic_download_done),
                contentDescription = stringResource(R.string.podcast_download_remove),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        TrackDownload.FAILED -> IconButton(onClick = onDownload) {
            Icon(
                painterResource(DesignR.drawable.ic_error),
                contentDescription = stringResource(R.string.podcast_download_retry),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        TrackDownload.NONE, TrackDownload.REMOVED -> IconButton(onClick = onDownload) {
            Icon(painterResource(DesignR.drawable.ic_download), contentDescription = stringResource(R.string.podcast_download_episode))
        }
    }
}

/**
 * Eine Folge der Abo-Seite bzw. ihrer Suche. Während der Mehrfachauswahl ([selection], Konzept 12.5) schaltet ein Tipp sie
 * dazu oder weg, die Knöpfe rechts entfallen und eine ausgewählte trägt einen Haken über dem Bild. [swipe]: Wischgesten
 * ([EpisodeSwipeBox]), nicht während der Auswahl und nicht in einem Pager („Gemerkt“). [podcast]: in Listen aus mehreren Abos
 * („Gemerkt“) der Name darunter.
 */
@Composable
internal fun EpisodeRow(
    item: BookWithProgress,
    withTime: Boolean,
    withNotes: Boolean,
    download: TrackDownload,
    downloadFraction: Float?,
    playing: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    selection: EpisodeSelection,
    swipe: Boolean,
    podcast: String? = null,
) {
    val book = item.book
    val progress = item.progress
    val listened = progress != null && progress.positionMs > 0
    var menu by remember { mutableStateOf(false) }
    val selecting = selection.active
    val selected = book.id in selection
    EpisodeSwipeBox(item, enabled = swipe && !selecting) { Box {
        ListItem(
            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    if (!podcast.isNullOrBlank()) Text(podcast, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (withNotes) {
                        val notes = remember(book.description, book.title) { firstDescriptionLine(book.description, book.title) }
                        if (notes.isNotEmpty()) Text(notes, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val facts = listOfNotNull(
                        episodeLabel(book, withSeason = false),
                        book.addedAt.takeIf { it > 0 }?.let { formatEpisodeDay(it, withTime) },
                        book.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                    ).joinToString(" · ")
                    // Gemerkte Folgen mit einem kleinen Stern vor Datum und Dauer.
                    if (facts.isNotEmpty() || book.starred) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (book.starred) StarredMark(Modifier.padding(end = 4.dp))
                            Text(facts)
                        }
                    }
                    if (progress != null && listened) {
                        if (progress.finished) {
                            Text(stringResource(R.string.spoken_finished))
                        } else {
                            LinearProgressIndicator(
                                progress = { progress.fraction },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            },
            leadingContent = { SelectableArt(book.id, selected, Modifier.size(56.dp)) },
            trailingContent = if (selecting) {
                null
            } else {
                {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Dateien vom Gerät sind schon da.
                        if (!book.isLocal) DownloadButton(download, downloadFraction, onDownload, onRemoveDownload)
                        IconButton(onClick = onPlay) {
                            if (playing) {
                                Icon(painterResource(DesignR.drawable.ic_pause), contentDescription = stringResource(R.string.action_pause))
                            } else {
                                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.podcast_play_episode))
                            }
                        }
                    }
                }
            },
            colors = if (selected) ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else ListItemDefaults.colors(),
            modifier = Modifier
                .combinedClickable(
                    onClick = { if (selecting) selection.toggle(book.id) else onClick() },
                    onLongClick = { if (selecting) selection.toggle(book.id) else menu = true },
                )
                .semantics { if (selecting) selectedSemantics = selected },
        )
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false }, onSelect = { selection.toggle(book.id) })
    } }
}

/** Quadratisches Podcast-Bild aus dem Feed; bis es geladen ist (oder ohne Bild) bleibt das Symbol sichtbar. */
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
