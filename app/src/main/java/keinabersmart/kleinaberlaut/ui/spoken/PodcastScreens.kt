package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.ui.text.AnnotatedString
import android.content.ClipData
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import keinabersmart.kleinaberlaut.core.common.normalizeForMerge
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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.podcast.FeedEpisodes
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.SubscribeResult
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.core.download.DownloadStart
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloadProgress
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import keinabersmart.kleinaberlaut.core.model.PodcastFeed
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

/** Zustand des „Podcast hinzufügen“-Dialogs. */
sealed interface AddPodcastState {
    data object Idle : AddPodcastState
    data object Working : AddPodcastState
    data class Failed(@param:StringRes val message: Int) : AddPodcastState
    data class Done(val feedId: String) : AddPodcastState
}

@HiltViewModel
class PodcastViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val refresher: PodcastRefresher,
    private val launcher: BookLauncher,
    private val playback: PlaybackConnection,
    continueListening: ContinueListening,
) : ViewModel() {

    val continueListening: StateFlow<List<BookWithProgress>> = continueListening.episodes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Pro Abo die noch nicht zu Ende gehörten Folgen. */
    val unheard: StateFlow<List<FeedEpisodes>> = podcasts.unheardByFeed()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** `null`, solange noch geladen wird. */
    val feeds: StateFlow<List<PodcastFeed>?> = podcasts.feeds
        .map<List<PodcastFeed>, List<PodcastFeed>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _add = MutableStateFlow<AddPodcastState>(AddPodcastState.Idle)
    val add: StateFlow<AddPodcastState> = _add

    fun subscribe(input: String) {
        if (_add.value == AddPodcastState.Working) return
        _add.value = AddPodcastState.Working
        viewModelScope.launch {
            _add.update {
                when (val result = podcasts.subscribe(input)) {
                    is SubscribeResult.Added -> AddPodcastState.Done(result.feedId)
                    is SubscribeResult.AlreadySubscribed -> AddPodcastState.Failed(R.string.podcast_already)
                    SubscribeResult.InvalidUrl -> AddPodcastState.Failed(R.string.podcast_error_invalid)
                    SubscribeResult.Unreachable -> AddPodcastState.Failed(R.string.podcast_error_unreachable)
                    SubscribeResult.NotAFeed -> AddPodcastState.Failed(R.string.podcast_error_not_feed)
                }
            }
        }
    }

    fun resetAdd() {
        _add.value = AddPodcastState.Idle
    }

    fun refreshAll() = refresher.refreshAll()

    /** Die Folge, die gerade läuft (nicht: pausiert). */
    val playingBookId: StateFlow<Long?> = playback.state
        .map { if (it.isPlaying) it.spoken?.bookId else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /**
     * Läuft die Folge, pausiert sie; ist sie geladen und pausiert, geht es weiter; sonst startet oder setzt sie fort
     * (und steht danach ganz vorn in „Weiterhören“).
     */
    fun playOrPause(bookId: Long) {
        if (playback.state.value.spoken?.bookId == bookId) playback.togglePlayPause() else launcher.play(bookId)
    }
}

/** Zustand und Dialog zum Abonnieren; nach dem Hinzufügen öffnet sich der neue Podcast. */
private class AddPodcastControl(val show: () -> Unit)

/** Zeigt bei Bedarf den „Podcast hinzufügen“-Dialog; [content] bekommt den Auslöser. */
@Composable
private fun AddPodcastHost(
    viewModel: PodcastViewModel,
    onFeedClick: (String) -> Unit,
    content: @Composable (AddPodcastControl) -> Unit,
) {
    val add by viewModel.add.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(add) {
        val state = add
        if (state is AddPodcastState.Done) {
            adding = false
            viewModel.resetAdd()
            onFeedClick(state.feedId)
        }
    }

    content(AddPodcastControl { adding = true })

    if (adding) {
        AddPodcastDialog(
            state = add,
            onSubscribe = viewModel::subscribe,
            onDismiss = {
                adding = false
                viewModel.resetAdd()
            },
        )
    }
}

/** Start im Modus „Podcasts“: Weiterhören (angefangene Folgen, die zuletzt gehörte links, neue am Ende) und die Abos. */
@Composable
fun PodcastHomeScreen(
    onEpisodeClick: (Long) -> Unit,
    onFeedClick: (String) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val episodes by viewModel.continueListening.collectAsStateWithLifecycle()
    val playingId by viewModel.playingBookId.collectAsStateWithLifecycle()
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val rowState = rememberLazyListState()
    // Wird eine andere Folge zur aktivsten, steht sie wieder ganz links sichtbar (die Zeile bliebe sonst am alten Eintrag stehen).
    LaunchedEffect(episodes.firstOrNull()?.book?.id) { rowState.scrollToItem(0) }
    AddPodcastHost(viewModel, onFeedClick) { addControl ->
        LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (episodes.isNotEmpty()) {
                item(key = "continue-header") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader(stringResource(R.string.spoken_continue), Modifier.weight(1f))
                        IconButton(onClick = onOpenQueue, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(painterResource(DesignR.drawable.ic_list), contentDescription = stringResource(R.string.player_queue))
                        }
                    }
                }
                item(key = "continue") {
                    LazyRow(
                        state = rowState,
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(episodes, key = { it.book.id }) { item ->
                            BookCard(
                                item,
                                onClick = { onEpisodeClick(item.book.id) },
                                modifier = Modifier.width(140.dp),
                                onPlay = { viewModel.playOrPause(item.book.id) },
                                playing = item.book.id == playingId,
                            )
                        }
                    }
                }
            }
            item(key = "subscriptions-header") { SectionHeader(stringResource(R.string.podcast_subscriptions)) }
            if (feeds?.isEmpty() == true) {
                item(key = "empty") { EmptyHint(stringResource(R.string.podcast_none)) }
            } else {
                item(key = "subscriptions") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(feeds.orEmpty(), key = { it.id }) { feed ->
                            FeedCard(feed, onClick = { onFeedClick(feed.id) }, modifier = Modifier.width(140.dp))
                        }
                    }
                }
            }
            item(key = "add") {
                FilledTonalButton(onClick = addControl.show, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                    Text(stringResource(R.string.podcast_add), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Bild und Titel eines Abos. */
@Composable
private fun FeedCard(feed: PodcastFeed, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(onClick = onClick)) {
        PodcastArt(feed.imageUrl, Modifier.fillMaxWidth())
        Text(
            feed.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private enum class PodcastTab(@StringRes val label: Int) {
    EPISODES(R.string.podcast_episodes),
    FEEDS(R.string.podcast_tab_feeds),
}

/** Bibliothek im Modus „Podcasts“: Reiter Folgen (ungehörte je Abo) und Abos (Raster mit schwebendem „+“). */
@Composable
fun PodcastLibraryScreen(
    onFeedClick: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
    val unheard by viewModel.unheard.collectAsStateWithLifecycle()
    val pagerState = rememberPagerState { PodcastTab.entries.size }
    val scope = rememberCoroutineScope()

    AddPodcastHost(viewModel, onFeedClick) { addControl ->
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
                    PodcastTab.EPISODES -> UnheardEpisodes(unheard, hasFeeds = feeds?.isNotEmpty() == true, onFeedClick, onEpisodeClick)
                    PodcastTab.FEEDS -> FeedGrid(feeds.orEmpty(), onFeedClick, onAdd = addControl.show)
                }
            }
        }
    }
}

/** Je Abo (untereinander) die ungehörten Folgen, horizontal scrollbar. */
@Composable
private fun UnheardEpisodes(
    unheard: List<FeedEpisodes>,
    hasFeeds: Boolean,
    onFeedClick: (String) -> Unit,
    onEpisodeClick: (Long) -> Unit,
) {
    if (unheard.isEmpty()) {
        EmptyHint(stringResource(if (hasFeeds) R.string.podcast_all_heard else R.string.podcast_none))
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        unheard.forEach { (feed, episodes) ->
            item(key = "header-${feed.id}") {
                Text(
                    feed.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { onFeedClick(feed.id) }.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            item(key = "row-${feed.id}") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(episodes, key = { it.book.id }) { item ->
                        BookCard(item, onClick = { onEpisodeClick(item.book.id) }, modifier = Modifier.width(140.dp))
                    }
                }
            }
        }
    }
}

/** Alle Abos als Bilder, drei nebeneinander; das „+“ schwebt unten rechts und bleibt beim Scrollen stehen. */
@Composable
private fun FeedGrid(feeds: List<PodcastFeed>, onFeedClick: (String) -> Unit, onAdd: () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (feeds.isEmpty()) {
            EmptyHint(stringResource(R.string.podcast_none))
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                // Unten Platz lassen, damit der Knopf das letzte Abo nicht verdeckt.
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 88.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(feeds, key = { it.id }) { feed -> FeedCard(feed, onClick = { onFeedClick(feed.id) }) }
            }
        }
        FloatingActionButton(onClick = onAdd, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            Icon(painterResource(DesignR.drawable.ic_add), contentDescription = stringResource(R.string.podcast_add))
        }
    }
}

@Composable
private fun AddPodcastDialog(state: AddPodcastState, onSubscribe: (String) -> Unit, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    val working = state == AddPodcastState.Working
    AlertDialog(
        onDismissRequest = { if (!working) onDismiss() },
        title = { Text(stringResource(R.string.podcast_add)) },
        text = {
            Column {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.podcast_add_url)) },
                    placeholder = { Text(stringResource(R.string.podcast_add_url_hint)) },
                    singleLine = true,
                    enabled = !working,
                    isError = state is AddPodcastState.Failed,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                when (state) {
                    is AddPodcastState.Failed -> Text(
                        stringResource(state.message),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    AddPodcastState.Working -> Text(
                        stringResource(R.string.podcast_adding),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubscribe(url) }, enabled = url.isNotBlank() && !working) {
                Text(stringResource(R.string.podcast_add_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@HiltViewModel(assistedFactory = PodcastFeedViewModel.Factory::class)
class PodcastFeedViewModel @AssistedInject constructor(
    @Assisted private val feedId: String,
    private val podcasts: PodcastRepository,
    private val launcher: BookLauncher,
    private val downloads: EpisodeDownloads,
    downloadProgress: EpisodeDownloadProgress,
) : ViewModel() {

    /** Anteil der gerade ladenden Folgen (0..1, [EpisodeDownloadProgress.UNKNOWN] ohne bekannte Größe). */
    val progress: StateFlow<Map<Long, Float>> = downloadProgress.fractions

    val feed: StateFlow<PodcastFeed?> = podcasts.feed(feedId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** `null`, solange noch geladen wird. */
    val episodes: StateFlow<List<BookWithProgress>?> = podcasts.episodes(feedId)
        .map<List<BookWithProgress>, List<BookWithProgress>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Download-Zustand je Folge. */
    val downloadStates: StateFlow<Map<Long, TrackDownload>> = podcasts.downloadStates(feedId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 2)

    /** Hinweise für den Nutzer (String-Ressource), z. B. dass ein Download auf eine Verbindung wartet. */
    val messages: SharedFlow<Int> = _messages.asSharedFlow()

    fun download(bookId: Long) {
        viewModelScope.launch {
            when (downloads.download(bookId)) {
                DownloadStart.OFFLINE -> _messages.emit(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI -> _messages.emit(R.string.podcast_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }

    fun removeDownload(bookId: Long) {
        viewModelScope.launch { downloads.remove(bookId) }
    }

    fun removeHeardDownloads() {
        viewModelScope.launch { downloads.removeHeard(feedId) }
    }

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { podcasts.setAutoDownload(feedId, enabled) }
    }

    fun setNewestFirst(enabled: Boolean) {
        viewModelScope.launch { podcasts.setNewestFirst(feedId, enabled) }
    }

    fun setContinuous(enabled: Boolean) {
        viewModelScope.launch { podcasts.setContinuous(feedId, enabled) }
    }

    fun unsubscribe() {
        viewModelScope.launch { podcasts.unsubscribe(feedId) }
    }

    fun play(bookId: Long) = launcher.play(bookId)

    @AssistedFactory
    interface Factory {
        fun create(feedId: String): PodcastFeedViewModel
    }
}

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
    val context = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
    }
    val current = feed

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {},
            navigationIcon = { BackButton(onBack) },
            actions = {
                IconButton(onClick = onSearch, enabled = current != null) {
                    Icon(painterResource(R.drawable.ic_nav_search), contentDescription = stringResource(R.string.podcast_search))
                }
            },
        )
        if (current == null) return@Column
        val list = episodes.orEmpty()
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
                            IconButton(onClick = onSettings, modifier = Modifier.size(40.dp)) {
                                Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = stringResource(R.string.podcast_settings))
                            }
                        }
                    }
                }
            }
            item(key = "episodes-header") { SectionHeader(stringResource(R.string.podcast_episodes)) }
            if (episodes != null && list.isEmpty()) {
                item(key = "empty") { EmptyHint(stringResource(R.string.podcast_no_episodes)) }
            }
            items(list, key = { it.book.id }) { item ->
                EpisodeRow(
                    item,
                    download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                    downloadFraction = downloadProgress[item.book.id],
                    onClick = { onEpisodeClick(item.book.id) },
                    onPlay = { viewModel.play(item.book.id) },
                    onDownload = { viewModel.download(item.book.id) },
                    onRemoveDownload = { viewModel.removeDownload(item.book.id) },
                )
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
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val downloadStates by viewModel.downloadStates.collectAsStateWithLifecycle()
    val downloadProgress by viewModel.progress.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }

    val needle = query.trim().normalizeForMerge()
    val hits = remember(episodes, needle) {
        if (needle.isEmpty()) {
            emptyList()
        } else {
            episodes.orEmpty().filter {
                it.book.title.normalizeForMerge().contains(needle) || descriptionForSearch(it.book.description).normalizeForMerge().contains(needle)
            }
        }
    }

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
            needle.isEmpty() -> Unit
            hits.isEmpty() -> EmptyHint(stringResource(R.string.search_no_results, query.trim()))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(hits, key = { it.book.id }) { item ->
                    EpisodeRow(
                        item,
                        download = downloadStates[item.book.id] ?: TrackDownload.NONE,
                        downloadFraction = downloadProgress[item.book.id],
                        onClick = { onEpisodeClick(item.book.id) },
                        onPlay = { viewModel.play(item.book.id) },
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
    val context = LocalContext.current
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
                Text(stringResource(R.string.podcast_feed_url), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 16.dp))
                Text(
                    current.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(current.title, current.url))) }
                        Toast.makeText(context, R.string.podcast_url_copied, Toast.LENGTH_SHORT).show()
                    },
                )
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

/** Einstellungen eines Podcasts: Wiedergabe, Downloads und Abo beenden. */
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
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "continuous") {
                SwitchRow(
                    title = stringResource(R.string.podcast_continuous),
                    summary = stringResource(R.string.podcast_continuous_summary),
                    icon = DesignR.drawable.ic_playlist_play,
                    checked = current.continuous,
                    onCheckedChange = viewModel::setContinuous,
                )
            }
            item(key = "newest-first") {
                SwitchRow(
                    title = stringResource(R.string.podcast_newest_first),
                    summary = stringResource(R.string.podcast_newest_first_summary),
                    icon = DesignR.drawable.ic_list,
                    checked = current.newestFirst,
                    onCheckedChange = viewModel::setNewestFirst,
                )
            }
            // Podcasts vom Gerät haben weder Feed noch Downloads und lassen sich nur durch Entfernen der Dateien beenden.
            if (!current.isLocal) item(key = "auto-download") {
                SwitchRow(
                    title = stringResource(R.string.podcast_auto_download),
                    summary = stringResource(R.string.podcast_auto_download_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = current.autoDownload,
                    onCheckedChange = viewModel::setAutoDownload,
                )
            }
            if (downloadStates.values.any { it == TrackDownload.DONE }) {
                item(key = "remove-heard") {
                    ActionRow(
                        title = stringResource(R.string.podcast_remove_heard_downloads),
                        summary = stringResource(R.string.podcast_remove_heard_downloads_summary),
                        icon = DesignR.drawable.ic_delete,
                        onClick = viewModel::removeHeardDownloads,
                    )
                }
            }
            if (!current.isLocal) item(key = "unsubscribe") {
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

    if (confirmUnsubscribe && current != null) {
        AlertDialog(
            onDismissRequest = { confirmUnsubscribe = false },
            title = { Text(stringResource(R.string.podcast_unsubscribe_title)) },
            text = { Text(stringResource(R.string.podcast_unsubscribe_message, current.title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUnsubscribe = false
                    viewModel.unsubscribe()
                    onUnsubscribed()
                }) { Text(stringResource(R.string.podcast_unsubscribe)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/**
 * Herunterladen; beim Laden ein Kreis, der sich füllt (leer = wartet, Tipp bricht ab), danach Haken (Tipp löscht).
 * Ist die Größe unbekannt, dreht sich der Kreis.
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
            Icon(painterResource(DesignR.drawable.ic_download_done), contentDescription = stringResource(R.string.podcast_download_remove))
        }
        TrackDownload.FAILED -> IconButton(onClick = onDownload) {
            Icon(painterResource(DesignR.drawable.ic_error), contentDescription = stringResource(R.string.podcast_download_retry))
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
                        Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.podcast_play_episode))
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
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
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
