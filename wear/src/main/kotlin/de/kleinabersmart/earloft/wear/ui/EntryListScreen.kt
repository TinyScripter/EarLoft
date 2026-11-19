package de.kleinabersmart.earloft.wear.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import androidx.wear.compose.ui.tooling.preview.WearPreviewDevices
import androidx.wear.compose.material3.MaterialTheme
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.wearsync.WearRemoteBookDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesRequest
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.launch

/** The watch's downloaded albums; with [artistId] only those of this artist, then [title] (their name) is shown at the top. */
@Composable
fun LocalAlbumsScreen(
    onClick: (Long) -> Unit,
    artistId: Long? = null,
    title: String? = null,
    viewModel: LocalAlbumsViewModel = hiltViewModel<LocalAlbumsViewModel, LocalAlbumsViewModel.Factory> { it.create(artistId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(title ?: stringResource(R.string.list_albums), state, onClick)
}

@Composable
fun LocalArtistsScreen(onClick: (artistId: Long, name: String) -> Unit, viewModel: LocalArtistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_artists), state) { id -> onClick(id, state.titleOf(id)) }
}

private fun EntryListState.titleOf(key: Long): String = (this as? EntryListState.Ready)?.entries?.firstOrNull { it.key == key }?.title.orEmpty()

@Composable
fun LocalPlaylistsScreen(onClick: (Long) -> Unit, viewModel: LocalPlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_playlists), state, onClick)
}

/**
 * The phone's albums; with [artistId] only those of this artist, then [title] (their name) is shown at the top; with [favorites] only the
 * favorited ones.
 */
@Composable
fun RemoteAlbumsScreen(
    onClick: (Long) -> Unit,
    artistId: Long? = null,
    title: String? = null,
    favorites: Boolean = false,
    viewModel: RemoteAlbumsViewModel = hiltViewModel<RemoteAlbumsViewModel, RemoteAlbumsViewModel.Factory> { it.create(artistId, favorites) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(title ?: stringResource(if (favorites) R.string.list_favorite_albums else R.string.list_albums), state, onClick)
}

/** The phone's artists; with [favorites] only the favorited ones. */
@Composable
fun RemoteArtistsScreen(
    onClick: (artistId: Long, name: String) -> Unit,
    favorites: Boolean = false,
    viewModel: RemoteArtistsViewModel = hiltViewModel<RemoteArtistsViewModel, RemoteArtistsViewModel.Factory> { it.create(favorites) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val title = stringResource(if (favorites) R.string.list_favorite_artists else R.string.list_artists)
    EntryListContent(title, state) { id -> onClick(id, state.titleOf(id)) }
}

@Composable
fun RemotePlaylistsScreen(onClick: (Long) -> Unit, viewModel: RemotePlaylistsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_playlists), state, onClick)
}

/** Podcasts ([BookKind.EPISODE]) or books on the watch; a tap plays here, [onPlayed] then opens the now playing view. */
@Composable
fun LocalSpokenScreen(
    kind: BookKind,
    onPlayed: () -> Unit,
    viewModel: LocalSpokenViewModel = hiltViewModel<LocalSpokenViewModel, LocalSpokenViewModel.Factory> { it.create(kind) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val title = stringResource(if (kind == BookKind.EPISODE) R.string.list_podcasts else R.string.list_books)
    EntryListContent(title, state) { bookId ->
        scope.launch { if (viewModel.play(bookId)) onPlayed() }
    }
}

/** The phone's queue and subscriptions; a tap opens the episodes ([onClick] with subscription and title). */
@Composable
fun RemotePodcastsScreen(onClick: (feedId: String, title: String) -> Unit, viewModel: RemotePodcastsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val queueTitle = stringResource(R.string.list_queue)
    EntryListContent(stringResource(R.string.list_podcasts), state) { key ->
        if (key == RemotePodcastsViewModel.QUEUE_KEY) {
            onClick(WearRemoteEpisodesRequest.QUEUE_FEED_ID, queueTitle)
        } else {
            viewModel.feedAt(key)?.let { onClick(it.feedId, it.title) }
        }
    }
}

/** The episodes of a subscription on the phone; [title] is the subscription's name, a tap opens the episode's page. */
@Composable
fun RemoteEpisodesScreen(
    feedId: String,
    title: String,
    onClick: (WearRemoteBookDto) -> Unit,
    viewModel: RemoteEpisodesViewModel = hiltViewModel<RemoteEpisodesViewModel, RemoteEpisodesViewModel.Factory> { it.create(feedId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(title, state) { key -> viewModel.episodeOf(key)?.let(onClick) }
}

/** The phone's audiobooks; a tap opens the book's page. */
@Composable
fun RemoteBooksScreen(onClick: (WearRemoteBookDto) -> Unit, viewModel: RemoteBooksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    EntryListContent(stringResource(R.string.list_books), state) { key -> viewModel.bookOf(key)?.let(onClick) }
}

@Composable
private fun EntryListContent(title: String, state: EntryListState, onClick: (Long) -> Unit) {
    val columnState = rememberTransformingLazyColumnState()
    val transformationSpec = rememberTransformationSpec()
    ScreenScaffold(scrollState = columnState) { contentPadding ->
        TransformingLazyColumn(
            state = columnState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = contentPadding,
        ) {
            item {
                ListHeader(
                    modifier = Modifier.transformedHeight(this, transformationSpec),
                    transformation = SurfaceTransformation(transformationSpec),
                ) {
                    Text(title, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            when (state) {
                EntryListState.Loading -> item {
                    CircularProgressIndicator(Modifier.transformedHeight(this, transformationSpec))
                }
                is EntryListState.Message -> item {
                    Text(
                        stringResource(state.textRes),
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                is EntryListState.Ready -> items(state.entries, key = { it.key }) { entry ->
                    val cover = entry.cover
                    val secondary = entry.secondaryText()
                    Button(
                        onClick = { onClick(entry.key) },
                        enabled = entry.enabled,
                        modifier = Modifier.fillMaxWidth().transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        icon = if (cover != null) { { CoverThumbnail(cover) } } else null,
                        label = { Text(entry.titleRes?.let { stringResource(it) } ?: entry.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        secondaryLabel = if (secondary != null) {
                            { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }
}

/** Second line of an entry; `null` if there is nothing to say (e.g. subscription without author). */
@Composable
private fun ListEntry.secondaryText(): String? {
    val loaded = loadedCount
    return when {
        !enabled -> stringResource(R.string.selection_not_loaded)
        loaded != null && loaded < songCount -> stringResource(R.string.selection_progress, loaded, songCount)
        started -> artist?.let { stringResource(R.string.list_started_by, it) } ?: stringResource(R.string.list_started)
        artist != null -> artist
        albumCount != null -> pluralStringResource(R.plurals.album_count, albumCount, albumCount)
        songCount > 0 -> pluralStringResource(R.plurals.song_count, songCount, songCount)
        else -> null
    }
}

@WearPreviewDevices
@Composable
private fun EntryListPreview() {
    MaterialTheme {
        EntryListContent(
            "Albums",
            EntryListState.Ready(
                listOf(
                    ListEntry(1, "Moonlight", 4, artist = "Night Moth", loadedCount = 4, cover = CoverRef.Album(1)),
                    ListEntry(2, "An album with a very long name", 12, artist = "Someone", loadedCount = 5, cover = CoverRef.Album(2)),
                ),
            ),
            onClick = {},
        )
    }
}
