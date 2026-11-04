package keinabersmart.kleinaberlaut.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import keinabersmart.kleinaberlaut.core.playback.PlayerState
import keinabersmart.kleinaberlaut.core.playback.QueueEntry
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.PlayPauseButton
import keinabersmart.kleinaberlaut.ui.player.PlayerViewModel
import keinabersmart.kleinaberlaut.ui.spoken.rememberContinueRowState
import keinabersmart.kleinaberlaut.ui.spoken.rowItemAnimation
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.ui.components.AlbumCard
import keinabersmart.kleinaberlaut.ui.components.ArtistCard
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.LoadFailedHint
import keinabersmart.kleinaberlaut.ui.components.PlayControlViewModel
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.components.Shelf
import keinabersmart.kleinaberlaut.ui.components.ShelfInset

@Composable
fun HomeScreen(
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onOpenQueue: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    playerViewModel: PlayerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val playControl: PlayControlViewModel = hiltViewModel()
    val playingAlbumId by playControl.playingAlbumId.collectAsStateWithLifecycle()
    val playerState by playerViewModel.state.collectAsStateWithLifecycle()
    val upNext = remember(playerState.current, playerState.queue, playerState.currentIndex) { upNext(playerState) }
    HomeContent(
        state = state,
        onAlbumClick = onAlbumClick,
        onArtistClick = onArtistClick,
        playingAlbumId = playingAlbumId,
        onPlayAlbum = playControl::playOrPauseAlbum,
        queue = QueueRowState(
            entries = upNext,
            currentIndex = playerState.currentIndex,
            isPlaying = playerState.isPlaying,
            onItemClick = { index ->
                if (index == playerState.currentIndex) playerViewModel.togglePlayPause() else playerViewModel.skipToQueueItem(index)
            },
            onOpen = onOpenQueue,
        ),
        modifier = modifier,
    )
}

/** Ein Titel der Zeile „Warteschlange“; [index] ist seine Stelle in der Warteschlange (eindeutig, auch bei doppelten Titeln). */
@Immutable
data class UpNext(val index: Int, val entry: QueueEntry)

/** Die Zeile „Warteschlange“: der laufende Titel und die folgenden ([entries]). */
@Immutable
class QueueRowState(
    val entries: List<UpNext>,
    val currentIndex: Int,
    val isPlaying: Boolean,
    val onItemClick: (Int) -> Unit,
    val onOpen: () -> Unit,
)

/**
 * Der laufende Titel und die nächsten in Reihenfolge der Warteschlange (bei Zufall wie in der Warteschlangen-Ansicht
 * nicht die Abspielreihenfolge). Leer, solange keine Musik läuft (etwa bei einem Buch).
 */
internal fun upNext(state: PlayerState): List<UpNext> {
    if (state.current == null || state.currentIndex !in state.queue.indices) return emptyList()
    return state.queue.withIndex().drop(state.currentIndex).take(UP_NEXT_COUNT).map { UpNext(it.index, it.value) }
}

private const val UP_NEXT_COUNT = 20

@Composable
private fun HomeContent(
    state: HomeUiState,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    playingAlbumId: Long? = null,
    onPlayAlbum: ((Long) -> Unit)? = null,
    queue: QueueRowState? = null,
) {
    Column(modifier.fillMaxSize()) {
        if (state.isSyncing) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        if (state.isFailed) {
            LoadFailedHint()
            return@Column
        }
        if (state.isEmpty) {
            EmptyHint(stringResource(if (state.isSyncing) R.string.library_syncing else R.string.library_empty))
            return@Column
        }
        val listState = rememberLazyListState()
        val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 } }
        val hasQueue = queue != null && queue.entries.isNotEmpty()
        // Kommt die Zeile „Warteschlange“ dazu, während die Liste oben steht, soll sie zu sehen sein; die Liste hielte
        // sonst den bisher obersten Abschnitt fest und die neue Zeile läge unsichtbar darüber. „Oben“ gilt für den Stand
        // vor dem Einfügen; im Effekt hat die Liste schon auf den bisherigen Abschnitt verschoben.
        val wasAtTop = atTop
        LaunchedEffect(hasQueue) { if (hasQueue && wasAtTop) listState.scrollToItem(0) }
        LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(vertical = 6.dp)) {
            if (hasQueue) queueSection(queue)
            albumSection("added", R.string.home_recently_added, state.recentlyAdded, onAlbumClick, playingAlbumId, onPlayAlbum)
            albumSection("rediscover", R.string.home_rediscover, state.rediscover, onAlbumClick, playingAlbumId, onPlayAlbum)
            albumSection("played", R.string.home_recently_played, state.recentlyPlayed, onAlbumClick, playingAlbumId, onPlayAlbum)
            if (state.randomArtists.isNotEmpty()) {
                item(key = "artists") {
                    Shelf(stringResource(R.string.home_random_artists)) {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = ShelfInset - CardInset),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            items(state.randomArtists, key = { it.id }) { artist ->
                                ArtistCard(artist = artist, onClick = { onArtistClick(artist.id) })
                            }
                        }
                    }
                }
            }
            albumSection("random", R.string.home_random_albums, state.randomAlbums, onAlbumClick, playingAlbumId, onPlayAlbum)
        }
    }
}

/**
 * Wie „Weiterhören“ bei Podcasts: Überschrift mit dem Symbol für die Warteschlange rechts, darunter der laufende Titel
 * und die folgenden. Ist ein Titel fertig, löst er sich auf und die Zeile rückt nach.
 */
private fun LazyListScope.queueSection(queue: QueueRowState) {
    item(key = "queue") {
        Shelf(
            stringResource(R.string.player_queue),
            action = {
                IconButton(onClick = queue.onOpen) {
                    Icon(painterResource(DesignR.drawable.ic_list), contentDescription = stringResource(R.string.player_open_queue))
                }
            },
        ) {
            val rowState = rememberContinueRowState(queue.entries.map { it.index.toLong() })
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = ShelfInset - CardInset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(queue.entries, key = { it.index }) { item ->
                    val current = item.index == queue.currentIndex
                    QueueCard(
                        entry = item.entry,
                        onClick = { queue.onItemClick(item.index) },
                        modifier = Modifier.width(144.dp).rowItemAnimation(this),
                        playing = current && queue.isPlaying,
                        isCurrent = current,
                    )
                }
            }
        }
    }
}

/** Innenabstand der Album-, Künstler- und Warteschlangen-Kacheln; die Reihen ziehen ihn vom Regal-Abstand ab. */
private val CardInset = 4.dp

/** Kachel eines Titels der Warteschlange wie eine Album-Kachel; der laufende trägt den Play-/Pause-Knopf. */
@Composable
private fun QueueCard(entry: QueueEntry, onClick: () -> Unit, modifier: Modifier, playing: Boolean, isCurrent: Boolean) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(4.dp),
    ) {
        Box {
            AlbumArt(albumId = entry.albumId, modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium)
            if (isCurrent) PlayPauseButton(playing, onClick, Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            entry.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Abschnitt mit Überschrift und horizontal scrollender Album-Reihe; leere Listen entfallen. */
private fun LazyListScope.albumSection(
    key: String,
    @StringRes title: Int,
    albums: List<Album>,
    onAlbumClick: (Long) -> Unit,
    playingAlbumId: Long?,
    onPlayAlbum: ((Long) -> Unit)?,
) {
    if (albums.isEmpty()) return
    item(key = key) {
        Shelf(stringResource(title)) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = ShelfInset - CardInset),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(albums, key = { it.id }) { album ->
                    AlbumCard(
                        album = album,
                        onClick = { onAlbumClick(album.id) },
                        width = 144.dp,
                        onPlay = onPlayAlbum?.let { play -> { play(album.id) } },
                        playing = album.id == playingAlbumId,
                        contentPadding = CardInset,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun HomeContentPreview() {
    KleinaberlautTheme {
        HomeContent(
            state = HomeUiState(
                recentlyAdded = List(4) { PreviewAlbum.copy(id = it.toLong()) },
                rediscover = List(4) { PreviewAlbum.copy(id = 10L + it) },
                recentlyPlayed = List(4) { PreviewAlbum.copy(id = 20L + it) },
                randomArtists = List(4) { Artist(id = it.toLong(), name = "Eine Band", albumCount = 2) },
                randomAlbums = List(4) { PreviewAlbum.copy(id = 30L + it) },
                isLoading = false,
            ),
            onAlbumClick = {},
            onArtistClick = {},
            queue = QueueRowState(
                entries = List(3) { UpNext(it, QueueEntry(songId = it.toLong(), title = "Ein Titel", artist = "Eine Band", albumId = null)) },
                currentIndex = 0,
                isPlaying = true,
                onItemClick = {},
                onOpen = {},
            ),
        )
    }
}
