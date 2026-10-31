package keinabersmart.kleinaberlaut.ui.spoken

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
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
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRefresher
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.data.podcast.SubscribeResult
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.model.PodcastFeed
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.ui.settings.SwitchRow
import kotlinx.coroutines.flow.MutableStateFlow
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
) : ViewModel() {

    val continueListening: StateFlow<List<BookWithProgress>> = podcasts.continueListening()
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
}

/** Start im Modus „Podcasts“: Weiterhören – angefangene Folgen, die zuletzt gehörte links, neue Folgen am Ende. */
@Composable
fun PodcastHomeScreen(
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val episodes by viewModel.continueListening.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        if (episodes.isEmpty()) {
            EmptyHint(stringResource(R.string.podcast_nothing_to_continue))
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "continue-header") { SectionHeader(stringResource(R.string.spoken_continue)) }
                item(key = "continue") {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(episodes, key = { it.book.id }) { item ->
                            BookCard(item, onClick = { onEpisodeClick(item.book.id) }, modifier = Modifier.width(140.dp))
                        }
                    }
                }
            }
        }
    }
}

/** Bibliothek im Modus „Podcasts“: Abos mit „Podcast hinzufügen“. */
@Composable
fun PodcastLibraryScreen(
    onFeedClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastViewModel = hiltViewModel(),
) {
    val feeds by viewModel.feeds.collectAsStateWithLifecycle()
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

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "header") { SectionHeader(stringResource(R.string.podcast_subscriptions)) }
        item(key = "add") {
            FilledTonalButton(onClick = { adding = true }, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null)
                Text(stringResource(R.string.podcast_add), modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (feeds?.isEmpty() == true) {
            item(key = "empty") { EmptyHint(stringResource(R.string.podcast_none)) }
        }
        items(feeds.orEmpty(), key = { it.id }) { feed ->
            ListItem(
                headlineContent = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = feed.author.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                leadingContent = { PodcastArt(feed.imageUrl, Modifier.size(56.dp), MaterialTheme.shapes.small) },
                modifier = Modifier.clickable { onFeedClick(feed.id) },
            )
        }
    }

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
) : ViewModel() {

    val feed: StateFlow<PodcastFeed?> = podcasts.feed(feedId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** `null`, solange noch geladen wird. */
    val episodes: StateFlow<List<BookWithProgress>?> = podcasts.episodes(feedId)
        .map<List<BookWithProgress>, List<BookWithProgress>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setAutoDownload(enabled: Boolean) {
        viewModelScope.launch { podcasts.setAutoDownload(feedId, enabled) }
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

/** Detailseite eines Podcasts: Einstellung für neue Folgen, Abspielen und alle Folgen. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastScreen(
    feedId: String,
    onBack: () -> Unit,
    onEpisodeClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastFeedViewModel = hiltViewModel<PodcastFeedViewModel, PodcastFeedViewModel.Factory> { it.create(feedId) },
) {
    val feed by viewModel.feed.collectAsStateWithLifecycle()
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    var confirmUnsubscribe by rememberSaveable { mutableStateOf(false) }
    val current = feed

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(current?.title.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { BackButton(onBack) },
        )
        if (current == null) return@Column
        val list = episodes.orEmpty()
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    PodcastArt(current.imageUrl, Modifier.size(180.dp))
                    Text(current.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp))
                    if (current.author.isNotBlank()) {
                        Text(current.author, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (current.description.isNotBlank()) {
                        Text(
                            current.description,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 5,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                    FilledTonalButton(
                        // Die neueste Folge, die noch nicht zu Ende gehört ist; sonst die neueste.
                        onClick = { (list.firstOrNull { it.progress?.finished != true } ?: list.firstOrNull())?.let { viewModel.play(it.book.id) } },
                        enabled = list.isNotEmpty(),
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null)
                        Text(stringResource(R.string.podcast_play_latest), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
            item(key = "auto-download") {
                SwitchRow(
                    title = stringResource(R.string.podcast_auto_download),
                    summary = stringResource(R.string.podcast_auto_download_summary),
                    icon = DesignR.drawable.ic_download,
                    checked = current.autoDownload,
                    onCheckedChange = viewModel::setAutoDownload,
                )
            }
            item(key = "episodes-header") { SectionHeader(stringResource(R.string.podcast_episodes)) }
            if (episodes != null && list.isEmpty()) {
                item(key = "empty") { EmptyHint(stringResource(R.string.podcast_no_episodes)) }
            }
            items(list, key = { it.book.id }) { item ->
                EpisodeRow(item, onClick = { onEpisodeClick(item.book.id) }, onPlay = { viewModel.play(item.book.id) })
            }
            item(key = "unsubscribe") {
                TextButton(onClick = { confirmUnsubscribe = true }, modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                    Text(stringResource(R.string.podcast_unsubscribe))
                }
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
                    onBack()
                }) { Text(stringResource(R.string.podcast_unsubscribe)) }
            },
            dismissButton = { TextButton(onClick = { confirmUnsubscribe = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun EpisodeRow(item: BookWithProgress, onClick: () -> Unit, onPlay: () -> Unit) {
    val book = item.book
    val progress = item.progress
    val listened = progress != null && progress.positionMs > 0
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
            IconButton(onClick = onPlay) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = stringResource(R.string.podcast_play_episode))
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
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
