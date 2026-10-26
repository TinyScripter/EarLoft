package keinabersmart.kleinaberlaut.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    QueueContent(
        state = state,
        onBack = onBack,
        onItemClick = viewModel::skipToQueueItem,
        onRemove = viewModel::removeQueueItem,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueContent(
    state: PlayerState,
    onBack: () -> Unit,
    onItemClick: (Int) -> Unit,
    onRemove: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.player_queue)) },
            navigationIcon = { BackButton(onBack) },
        )
        if (state.queue.isEmpty()) {
            EmptyHint(stringResource(R.string.player_nothing_playing))
            return@Column
        }
        val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.currentIndex.coerceAtLeast(0))
        // Einträge können doppelt vorkommen; die Position ist der einzige eindeutige Schlüssel.
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding()) {
            itemsIndexed(state.queue) { index, entry ->
                val isCurrent = index == state.currentIndex
                ListItem(
                    headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(entry.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingContent = {
                        AlbumArt(albumId = entry.albumId, modifier = Modifier.size(48.dp), shape = MaterialTheme.shapes.small)
                    },
                    trailingContent = {
                        IconButton(onClick = { onRemove(index) }) {
                            Icon(
                                painterResource(DesignR.drawable.ic_close),
                                contentDescription = stringResource(R.string.action_remove_from_queue),
                            )
                        }
                    },
                    colors = if (isCurrent) {
                        ListItemDefaults.colors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            headlineColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    } else {
                        ListItemDefaults.colors()
                    },
                    modifier = Modifier.clickable { onItemClick(index) },
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun QueueContentPreview() {
    KleinaberlautTheme {
        QueueContent(
            state = PlayerState(
                queue = List(5) { QueueEntry(songId = it.toLong(), title = "Lied ${it + 1}", artist = "Eine Band", albumId = null) },
                currentIndex = 1,
            ),
            onBack = {},
            onItemClick = {},
            onRemove = {},
        )
    }
}
