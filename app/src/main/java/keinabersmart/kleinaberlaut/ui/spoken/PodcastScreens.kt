package keinabersmart.kleinaberlaut.ui.spoken

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.FeedEpisodes
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloadProgress
import keinabersmart.kleinaberlaut.core.model.PodcastFeed
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.network.podcast.ardsounds.ardSoundsPageUrl
import keinabersmart.kleinaberlaut.core.network.podcast.ardsounds.ardSoundsShowId
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import keinabersmart.kleinaberlaut.ui.components.LoadableList
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.CoverOutlineWidth
import keinabersmart.kleinaberlaut.ui.components.GridTilePadding
import keinabersmart.kleinaberlaut.ui.components.group
import keinabersmart.kleinaberlaut.ui.components.groupedItems
import keinabersmart.kleinaberlaut.ui.components.Shelf
import keinabersmart.kleinaberlaut.ui.components.coverOutlineColor
import keinabersmart.kleinaberlaut.ui.components.gridTile
import keinabersmart.kleinaberlaut.ui.components.ShelfInset
import keinabersmart.kleinaberlaut.ui.valueOrNull
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.settings.SwitchRow
import keinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

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
    val rowState = rememberContinueRowState(episodes.map { it.book.id })
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
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.book.id }) { item ->
                            BookCard(
                                item,
                                onClick = { onEpisodeClick(item.book.id) },
                                modifier = Modifier.width(140.dp).rowItemAnimation(this),
                                onPlay = { viewModel.playOrPause(item.book.id) },
                                playing = item.book.id == playingId,
                            )
                        }
                    }
                }
            }
        }
        if (recent.isNotEmpty()) {
            item(key = "recent") {
                Shelf(stringResource(R.string.spoken_recent)) {
                    BookRow(recent, onEpisodeClick, playingId = playingId, onPlay = viewModel::playOrPause)
                }
            }
        }
        item(key = "subscriptions") {
            Shelf(stringResource(R.string.podcast_subscriptions)) {
                val list = feeds.valueOrNull
                if (feeds == LoadState.Failed) {
                    LoadFailedHint()
                } else if (list?.isEmpty() == true) {
                    EmptyHint(stringResource(R.string.podcast_none))
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(list.orEmpty(), key = { it.id }) { feed ->
                            FeedCard(
                                feed,
                                onClick = { onFeedClick(feed.id) },
                                onSettings = { onFeedSettings(feed.id) },
                                modifier = Modifier.width(140.dp),
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
 * Bild und Titel eines Abos, oben links auf dem Bild die Zahl der geladenen Folgen ([FeedDownloadBadge]); Langklick öffnet
 * das [FeedMenu], dessen letzter Eintrag die Einstellungen ([onSettings]).
 */
@Composable
fun FeedCard(
    feed: PodcastFeed,
    onClick: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: Dp = 0.dp,
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
            FeedDownloadBadge(feed.id, Modifier.align(Alignment.TopStart).padding(6.dp))
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
    FEEDS(R.string.podcast_tab_feeds),
    STATS(R.string.podcast_tab_stats),
}

/** Bibliothek im Modus „Podcasts“: Reiter Folgen (ungehörte je Abo), Abos (Raster mit schwebendem „+“) und Statistik. */
@Composable
fun PodcastLibraryScreen(
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    onAddPodcast: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val unheard by viewModel.unheard.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { PodcastTab.entries.size }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        PrimaryTabRow(selectedTabIndex = pagerState.currentPage) {
            PodcastTab.entries.forEachIndexed { index, tab ->
                Tab(
                    selected = pagerState.currentPage == index,
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    text = { Text(stringResource(tab.label)) },
                )
            }
        }
        HorizontalPager(state = pagerState, modifier = Modifier.weight(1f).fillMaxWidth(), key = { it }) { page ->
            when (PodcastTab.entries[page]) {
                PodcastTab.EPISODES -> UnheardEpisodes(
                    unheard,
                    hasFeeds = feeds.valueOrNull?.isNotEmpty() == true,
                    onFeedClick,
                    onEpisodeClick,
                    playingId,
                    viewModel::playOrPause,
                )
                PodcastTab.FEEDS -> FeedGrid(feeds, onFeedClick, onFeedSettings, onAdd = onAddPodcast)
                PodcastTab.STATS -> PodcastStatsScreen(onFeedClick)
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
) {
    LoadableList(state, stringResource(if (hasFeeds) R.string.podcast_all_heard else R.string.podcast_none)) { unheard ->
        UnheardList(unheard, onFeedClick, onEpisodeClick, playingId, onPlay)
    }
}

@Composable
private fun UnheardList(
    unheard: List<FeedEpisodes>,
    onFeedClick: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    playingId: Long?,
    onPlay: (Long) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 6.dp, bottom = 16.dp)) {
        unheard.forEach { (feed, episodes) ->
            item(key = "feed-${feed.id}") {
                Shelf(feed.title, onTitleClick = { onFeedClick(feed.id) }) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = ShelfInset),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.book.id }) { item ->
                            BookCard(
                                item,
                                onClick = { onEpisodeClick(item.book.id) },
                                modifier = Modifier.width(140.dp),
                                onPlay = { onPlay(item.book.id) },
                                playing = item.book.id == playingId,
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
    onFeedClick: (String) -> Unit,
    onFeedSettings: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Box(Modifier.fillMaxSize()) {
        LoadableList(state, stringResource(R.string.podcast_none)) { feeds ->
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
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
                        modifier = Modifier.gridTile(MaterialTheme.colorScheme.surfaceContainer),
                        contentPadding = GridTilePadding,
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
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
    }
    val current = feed

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = { BackButton(onBack) },
            // Der Aktualisieren-Knopf steht ganz rechts wie in den anderen Bildschirmen, die Suche links davon.
            actions = {
                IconButton(onClick = onSearch, enabled = current != null) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_search))
                }
                IconButton(onClick = viewModel::refresh, enabled = current != null) {
                    Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = stringResource(R.string.podcast_refresh_feed))
                }
            },
        )
        if (current == null) return@Column
        val list = episodes.valueOrNull.orEmpty()
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
                item(key = "episodes-header") { SectionHeader(stringResource(R.string.podcast_episodes)) }
                if (episodes == LoadState.Failed) {
                    item(key = "failed") { LoadFailedHint() }
                } else if (episodes is LoadState.Ready && list.isEmpty()) {
                    item(key = "empty") { EmptyHint(stringResource(R.string.podcast_no_episodes)) }
                }
                groupedItems(list, key = { it.book.id }) { item ->
                    EpisodeRow(
                        item,
                        download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[item.book.id],
                        playing = item.book.id == playingId,
                        onClick = { onEpisodeClick(item.book.id) },
                        onPlay = { viewModel.playOrPause(item.book.id) },
                        onDownload = { viewModel.download(item.book.id) },
                        onRemoveDownload = { viewModel.removeDownload(item.book.id) },
                    )
                }
            }
        }
    }
}

private val HEADER_ART_SIZE = 144.dp

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

    val trimmed = query.trim()
    // Beim Tippen bleiben die letzten Treffer stehen, bis die neuen da sind; „Nichts gefunden“ erst für den aktuellen Text.
    val hits = result?.hits.orEmpty()
    val settled = result?.query == trimmed

    Column(modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
        feed?.let {
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
                groupedItems(hits, key = { it.book.id }) { item ->
                    EpisodeRow(
                        item,
                        download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[item.book.id],
                        playing = item.book.id == playingId,
                        onClick = { onEpisodeClick(item.book.id) },
                        onPlay = { viewModel.playOrPause(item.book.id) },
                        onDownload = { viewModel.download(item.book.id) },
                        onRemoveDownload = { viewModel.removeDownload(item.book.id) },
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
    val current = feed
    val clipboard = LocalClipboard.current
    val snackbars = LocalSnackbars.current
    val copiedText = stringResource(R.string.podcast_url_copied)
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.podcast_info), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        if (current == null) return@Column
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

/** Einstellungen eines Podcasts: Wiedergabe, Downloads und Abo beenden oder archivieren. */
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
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    val current = feed

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.podcast_settings), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        if (current == null) return@Column
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp)) {
            group("podcast-settings") {
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
                // Podcasts vom Gerät haben weder Feed noch Downloads und lassen sich nur durch Entfernen der Dateien beenden.
                if (!current.isLocal) row("auto-download") {
                    SwitchRow(
                        title = stringResource(R.string.podcast_auto_download),
                        summary = stringResource(R.string.podcast_auto_download_summary),
                        icon = DesignR.drawable.ic_download,
                        checked = current.autoDownload,
                        onCheckedChange = viewModel::setAutoDownload,
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

    if (confirmUnsubscribe && current != null) {
        val snackbars = LocalSnackbars.current
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

@Composable
private fun EpisodeRow(
    item: BookWithProgress,
    download: TrackDownload,
    downloadFraction: Float?,
    playing: Boolean,
    onClick: () -> Unit,
    onPlay: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val book = item.book
    val progress = item.progress
    val listened = progress != null && progress.positionMs > 0
    var menu by remember { mutableStateOf(false) }
    Box {
        ListItem(
            headlineContent = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                Column {
                    val facts = listOfNotNull(
                        book.addedAt.takeIf { it > 0 }?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it)) },
                        book.durationMs.takeIf { it > 0 }?.let(::formatDuration),
                    ).joinToString(" · ")
                    if (facts.isNotEmpty()) Text(facts)
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
            leadingContent = { BookArt(book.id, Modifier.size(56.dp), MaterialTheme.shapes.small) },
            trailingContent = {
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
            },
            modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = { menu = true }),
        )
        EpisodeMenu(item, expanded = menu, onDismiss = { menu = false })
    }
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
