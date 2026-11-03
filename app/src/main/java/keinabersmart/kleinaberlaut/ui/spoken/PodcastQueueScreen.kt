package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.asLoadState
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import keinabersmart.kleinaberlaut.ui.components.LoadingIndicator
import keinabersmart.kleinaberlaut.ui.valueOrNull
import keinabersmart.kleinaberlaut.ui.library.BackButton
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@HiltViewModel
class PodcastQueueViewModel @Inject constructor(
    private val continueListening: ContinueListening,
    private val launcher: BookLauncher,
    playback: PlaybackConnection,
) : ViewModel() {

    val episodes: StateFlow<LoadState<List<BookWithProgress>>> = continueListening.episodes
        .asLoadState()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Loading)

    /** Die gerade geladene Folge. */
    val currentBookId: StateFlow<Long?> = playback.state.map { it.spoken?.bookId }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun play(bookId: Long) = launcher.play(bookId)

    fun remove(bookId: Long) {
        viewModelScope.launch { continueListening.remove(bookId) }
    }

    fun reorder(bookIds: List<Long>) {
        viewModelScope.launch { continueListening.reorder(bookIds) }
    }
}

/** Die Warteschlange der Podcasts („Weiterhören“) bearbeiten: wie bei der Musik mit Ziehgriff und ✕. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PodcastQueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PodcastQueueViewModel = hiltViewModel(),
) {
    val episodes by viewModel.episodes.collectAsStateWithLifecycle()
    val currentId by viewModel.currentBookId.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Die lokale Kopie wird beim Ziehen sofort umsortiert und erst am Ende des Ziehens gespeichert.
    val rows = remember { mutableStateListOf<BookWithProgress>() }
    var draggedId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    LaunchedEffect(episodes) {
        val loaded = episodes.valueOrNull
        if (draggedId != null || loaded == null) return@LaunchedEffect
        rows.clear()
        rows.addAll(loaded)
    }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.player_queue)) },
            navigationIcon = { BackButton(onBack) },
        )
        when (episodes) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing, LoadState.Failed -> LoadFailedHint()
            // Die Kopie zieht erst im nächsten Frame nach; leer ist die Liste erst, wenn auch die Quelle leer ist.
            is LoadState.Ready -> if (rows.isEmpty() && episodes.valueOrNull.isNullOrEmpty()) {
                EmptyHint(stringResource(R.string.podcast_queue_empty))
            }
        }
        if (rows.isEmpty()) return@Column
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            items(rows, key = { it.book.id }) { item ->
                val id = item.book.id
                val isDragged = id == draggedId
                ListItem(
                    headlineContent = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(item.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painterResource(DesignR.drawable.ic_drag_handle),
                                contentDescription = stringResource(R.string.action_reorder_queue_item),
                                modifier = Modifier
                                    .size(40.dp)
                                    .padding(8.dp)
                                    .pointerInput(id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedId = id
                                                dragOffset = 0f
                                            },
                                            onDragEnd = {
                                                draggedId = null
                                                dragOffset = 0f
                                                viewModel.reorder(rows.map { it.book.id })
                                            },
                                            onDragCancel = {
                                                draggedId = null
                                                dragOffset = 0f
                                                viewModel.reorder(rows.map { it.book.id })
                                            },
                                        ) { change, drag ->
                                            change.consume()
                                            dragOffset += drag.y
                                            val from = rows.indexOfFirst { it.book.id == id }
                                            val height = listState.layoutInfo.visibleItemsInfo
                                                .firstOrNull { it.key == id }?.size?.toFloat() ?: return@detectDragGestures
                                            val to = when {
                                                dragOffset > height / 2 -> from + 1
                                                dragOffset < -height / 2 -> from - 1
                                                else -> from
                                            }
                                            if (to != from && to in rows.indices) {
                                                rows.add(to, rows.removeAt(from))
                                                dragOffset -= (to - from) * height
                                            }
                                        }
                                    },
                            )
                            BookArt(id, Modifier.padding(start = 4.dp).size(48.dp), MaterialTheme.shapes.small)
                        }
                    },
                    trailingContent = {
                        IconButton(onClick = { viewModel.remove(id) }) {
                            Icon(
                                painterResource(DesignR.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_remove_from_queue),
                            )
                        }
                    },
                    colors = if (id == currentId) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier
                        .zIndex(if (isDragged) 1f else 0f)
                        .graphicsLayer { translationY = if (isDragged) dragOffset else 0f }
                        .clickable { viewModel.play(id) },
                )
            }
        }
    }
}
