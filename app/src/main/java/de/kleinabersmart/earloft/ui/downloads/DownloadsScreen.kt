package de.kleinabersmart.earloft.ui.downloads

import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import de.kleinabersmart.earloft.ui.library.AlbumMenu
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.ui.components.GroupMargin
import de.kleinabersmart.earloft.ui.components.LazyListFastScroller
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
import de.kleinabersmart.earloft.core.model.authorLine
import de.kleinabersmart.earloft.core.model.DownloadOverview
import de.kleinabersmart.earloft.core.model.DownloadedAlbum
import de.kleinabersmart.earloft.core.download.BookTransfer
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.ui.components.AlbumArt
import de.kleinabersmart.earloft.core.data.podcast.FeedDownload
import de.kleinabersmart.earloft.ui.spoken.BookArt
import de.kleinabersmart.earloft.ui.spoken.PodcastArt
import de.kleinabersmart.earloft.ui.spoken.transferText
import de.kleinabersmart.earloft.ui.components.EmptyHint
import de.kleinabersmart.earloft.ui.components.LoadFailedHint
import de.kleinabersmart.earloft.ui.components.LoadingIndicator
import de.kleinabersmart.earloft.ui.components.PreviewAlbum
import de.kleinabersmart.earloft.ui.formatBytes
import de.kleinabersmart.earloft.ui.library.BackButton
import de.kleinabersmart.earloft.core.designsystem.R as DesignR
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onBookClick: (Long) -> Unit,
    onPodcastClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val transfers by viewModel.transfers.collectAsStateWithLifecycle()
    DownloadsContent(
        state = state,
        transfers = transfers,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onBookClick = onBookClick,
        onPodcastClick = onPodcastClick,
        onRetryFailed = viewModel::retryFailed,
        onRemoveAlbum = viewModel::removeAlbum,
        onRemoveBook = viewModel::removeBook,
        onRemoveFeed = viewModel::removeFeed,
        onRemoveCategory = {
            when (it) {
                DownloadCategory.MUSIC -> viewModel.removeAllMusic()
                DownloadCategory.BOOKS -> viewModel.removeAllBooks()
                DownloadCategory.PODCASTS -> viewModel.removeAllPodcasts()
            }
        },
        onRemoveAll = viewModel::removeAll,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsContent(
    state: DownloadsUiState,
    transfers: Map<Long, BookTransfer>,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onBookClick: (Long) -> Unit,
    onPodcastClick: (String) -> Unit,
    onRetryFailed: () -> Unit,
    onRemoveAlbum: (Long) -> Unit,
    onRemoveBook: (Long) -> Unit,
    onRemoveFeed: (String) -> Unit,
    onRemoveCategory: (DownloadCategory) -> Unit,
    onRemoveAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var albumToRemove by rememberSaveable { mutableStateOf<Long?>(null) }
    var bookToRemove by rememberSaveable { mutableStateOf<Long?>(null) }
    var feedToRemove by rememberSaveable { mutableStateOf<String?>(null) }
    var categoryToRemove by rememberSaveable { mutableStateOf<DownloadCategory?>(null) }
    var confirmRemoveAll by rememberSaveable { mutableStateOf(false) }
    val isEmpty = state.albums.isEmpty() && state.books.isEmpty() && state.podcasts.isEmpty()
    val overview = state.overview

    Column(modifier.fillMaxSize().readableWidth()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_downloads)) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (!isEmpty) {
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.downloads_remove_all)) },
                                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    confirmRemoveAll = true
                                },
                            )
                        }
                    }
                }
            },
        )
        when {
            state.isLoading -> LoadingIndicator()
            state.isFailed -> LoadFailedHint()
            isEmpty && state.hiddenBytes <= 0 -> EmptyHint(stringResource(R.string.downloads_empty))
            else -> Box(Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()
                LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 16.dp)) {
                    item(key = "storage") { StorageLine(overview, state.hiddenBytes) }
                    group("status") {
                        if (state.storageShort) row("no-room") { NoRoomRow() }
                        if (overview.pendingSongs > 0) row("pending") { PendingRow(overview) }
                        if (overview.failedSongs > 0) row("failed") { FailedRow(overview.failedSongs, onRetryFailed) }
                    }
                    if (state.albums.isNotEmpty()) {
                        item(key = "albums-header") {
                            CategoryHeader(stringResource(R.string.downloads_section_music)) { categoryToRemove = DownloadCategory.MUSIC }
                        }
                    }
                    groupedItems(state.albums, key = { "album-${it.album.id}" }) { album ->
                        DownloadedAlbumRow(
                            album = album,
                            onClick = { onAlbumClick(album.album.id) },
                            onRemove = { albumToRemove = album.album.id },
                        )
                    }
                    if (state.books.isNotEmpty()) {
                        item(key = "books-header") {
                            CategoryHeader(stringResource(R.string.downloads_section_books)) { categoryToRemove = DownloadCategory.BOOKS }
                        }
                    }
                    groupedItems(state.books, key = { "book-${it.book.id}" }) { item ->
                        DownloadedBookRow(
                            item = item,
                            transfer = transfers[item.book.id],
                            onClick = { onBookClick(item.book.id) },
                            // A waiting or running download is cancelled without confirmation; a fully or partially
                            // downloaded one is deleted, after confirmation.
                            onRemove = {
                                if (item.download.state == TrackDownload.DONE || item.download.partial) {
                                    bookToRemove = item.book.id
                                } else {
                                    onRemoveBook(item.book.id)
                                }
                            },
                        )
                    }
                    if (state.podcasts.isNotEmpty()) {
                        item(key = "podcasts-header") {
                            CategoryHeader(stringResource(R.string.downloads_section_podcasts)) { categoryToRemove = DownloadCategory.PODCASTS }
                        }
                    }
                    groupedItems(state.podcasts, key = { "podcast-${it.feed.id}" }) { item ->
                        DownloadedFeedRow(
                            item = item,
                            onClick = { onPodcastClick(item.feed.id) },
                            onRemove = { feedToRemove = item.feed.id },
                        )
                    }
                }
                // Without a mark: three sections with their own order.
                LazyListFastScroller(listState)
            }
        }
    }

    val removing = state.albums.firstOrNull { it.album.id == albumToRemove }
    if (removing != null) {
        ConfirmDialog(
            title = stringResource(R.string.download_album_remove_title),
            text = stringResource(R.string.download_album_remove_text, removing.album.title),
            onConfirm = { onRemoveAlbum(removing.album.id) },
            onDismiss = { albumToRemove = null },
        )
    }
    val removingBook = state.books.firstOrNull { it.book.id == bookToRemove }
    if (removingBook != null) {
        ConfirmDialog(
            title = stringResource(R.string.book_download_delete_title),
            text = stringResource(R.string.book_download_delete_message, formatBytes(removingBook.download.bytes)),
            onConfirm = { onRemoveBook(removingBook.book.id) },
            onDismiss = { bookToRemove = null },
        )
    }
    val removingFeed = state.podcasts.firstOrNull { it.feed.id == feedToRemove }
    if (removingFeed != null) {
        ConfirmDialog(
            title = stringResource(R.string.podcast_downloads_delete_title, removingFeed.feed.title),
            text = stringResource(R.string.podcast_downloads_delete_message, formatBytes(removingFeed.bytes)),
            onConfirm = { onRemoveFeed(removingFeed.feed.id) },
            onDismiss = { feedToRemove = null },
        )
    }
    categoryToRemove?.let { category ->
        ConfirmDialog(
            title = stringResource(
                when (category) {
                    DownloadCategory.MUSIC -> R.string.downloads_remove_music_title
                    DownloadCategory.BOOKS -> R.string.downloads_remove_books_title
                    DownloadCategory.PODCASTS -> R.string.downloads_remove_podcasts_title
                },
            ),
            text = stringResource(R.string.downloads_remove_category_text),
            onConfirm = { onRemoveCategory(category) },
            onDismiss = { categoryToRemove = null },
        )
    }
    if (confirmRemoveAll) {
        ConfirmDialog(
            title = stringResource(R.string.downloads_remove_all_title),
            text = stringResource(if (state.hiddenBytes > 0) R.string.downloads_remove_all_text_shown else R.string.downloads_remove_all_text),
            onConfirm = onRemoveAll,
            onDismiss = { confirmRemoveAll = false },
        )
    }
}

@Composable
private fun StorageLine(overview: DownloadOverview, hiddenBytes: Long) {
    Column(Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.downloads_storage, formatBytes(overview.usedBytes), formatBytes(overview.freeBytes)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (hiddenBytes > 0) {
            Text(
                stringResource(R.string.downloads_hidden_areas, formatBytes(hiddenBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private const val WAITING_HINT_DELAY_MS = 3_000L

@Composable
private fun PendingRow(overview: DownloadOverview) {
    ListItem(
        headlineContent = {
            Text(pluralStringResource(R.plurals.downloads_pending, overview.pendingSongs, overview.pendingSongs))
        },
        supportingContent = {
            val progress = overview.currentProgress
            // Between two songs the progress is briefly zero. The bar stays, and the (taller)
            // hint only appears after a pause, otherwise the list jumps with every finished song.
            var waiting by remember { mutableStateOf(false) }
            LaunchedEffect(progress == null) {
                waiting = false
                if (progress == null) {
                    delay(WAITING_HINT_DELAY_MS)
                    waiting = true
                }
            }
            Column(Modifier.padding(top = 8.dp)) {
                LinearProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth())
                if (waiting) {
                    // No download running: waiting for network (or Wi-Fi) or storage.
                    Text(stringResource(R.string.downloads_waiting_hint), Modifier.padding(top = 8.dp))
                }
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
    )
}

/** Downloads wait for storage (review H7): they continue by themselves, nothing to retry. */
@Composable
private fun NoRoomRow() {
    ListItem(
        headlineContent = { Text(stringResource(R.string.downloads_no_room)) },
        supportingContent = { Text(stringResource(R.string.downloads_no_room_hint)) },
        leadingContent = {
            Icon(painterResource(DesignR.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
    )
}

@Composable
private fun FailedRow(count: Int, onRetry: () -> Unit) {
    ListItem(
        headlineContent = { Text(pluralStringResource(R.plurals.download_album_failed, count, count)) },
        leadingContent = {
            Icon(painterResource(DesignR.drawable.ic_error), contentDescription = null, tint = MaterialTheme.colorScheme.error)
        },
        trailingContent = { TextButton(onClick = onRetry) { Text(stringResource(R.string.downloads_retry)) } },
    )
}

@Composable
private fun DownloadedAlbumRow(album: DownloadedAlbum, onClick: () -> Unit, onRemove: () -> Unit) {
    // Long press: the album menu, as everywhere else an album appears.
    var menu by remember { mutableStateOf(false) }
    Box {
    if (menu) AlbumMenu(album.album, onDismiss = { menu = false })
    ListItem(
        headlineContent = { Text(album.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(album.album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(
                        R.plurals.downloads_album_summary,
                        album.album.songCount,
                        album.downloadedSongs,
                        album.album.songCount,
                        formatBytes(album.sizeBytes),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = {
            AlbumArt(albumId = album.album.id, modifier = Modifier.size(56.dp), shape = MaterialTheme.shapes.small)
        },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = stringResource(R.string.action_remove_download))
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClickLabel = stringResource(R.string.action_more),
            onLongClick = { menu = true },
        ),
    )
    }
}

/**
 * Category header with a small red trash can on the right. Margin and touch target match the trash can of the
 * rows (ListItem: 16 dp margin, 48 dp button) so both line up vertically.
 */
@Composable
private fun CategoryHeader(text: String, onRemoveAll: () -> Unit) {
    Row(
        // The trash can is aligned with those of the rows in the group below.
        Modifier.fillMaxWidth().padding(start = GroupMargin + 16.dp, end = GroupMargin + 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Styled like the section headers of the other lists (SectionHeader).
        Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        IconButton(onClick = onRemoveAll) {
            Icon(
                painterResource(DesignR.drawable.ic_delete),
                contentDescription = stringResource(R.string.downloads_remove_category),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/** Podcast subscription with image and the total of its downloaded episodes (one entry per subscription); tap opens the podcast. */
@Composable
private fun DownloadedFeedRow(item: FeedDownload, onClick: () -> Unit, onRemove: () -> Unit) {
    ListItem(
        headlineContent = { Text(item.feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                if (item.feed.author.isNotBlank()) Text(item.feed.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    pluralStringResource(R.plurals.downloads_podcast_summary, item.episodes, item.episodes, formatBytes(item.bytes)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        leadingContent = { PodcastArt(item.feed.imageUrl, Modifier.size(56.dp), MaterialTheme.shapes.small) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = stringResource(R.string.action_remove_download))
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Groups of the downloads overview that can be deleted as a whole. */
enum class DownloadCategory { MUSIC, BOOKS, PODCASTS }

/** Book with cover and status: size, "32 MB of 500 MB downloaded", waiting or error; tap opens the book page. */
@Composable
private fun DownloadedBookRow(item: DownloadedBook, transfer: BookTransfer?, onClick: () -> Unit, onRemove: () -> Unit) {
    val state = item.download.state
    ListItem(
        headlineContent = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(item.book.authorLine, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (state) {
                    TrackDownload.DONE -> Text(formatBytes(item.download.bytes), style = MaterialTheme.typography.bodySmall)
                    TrackDownload.FAILED -> Text(
                        if (item.download.partial) {
                            pluralStringResource(R.plurals.book_download_partial, item.download.totalFiles, item.download.doneFiles, item.download.totalFiles)
                        } else {
                            stringResource(R.string.book_download_failed)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Text(transferText(transfer), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        leadingContent = { BookArt(item.book.id, Modifier.size(56.dp), MaterialTheme.shapes.small) },
        trailingContent = {
            IconButton(onClick = onRemove) {
                if (state == TrackDownload.DONE || state == TrackDownload.FAILED) {
                    Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = stringResource(R.string.action_remove_download))
                } else {
                    Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel_download))
                }
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun ConfirmDialog(title: String, text: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = {
            DestructiveTextButton(onClick = {
                onConfirm()
                onDismiss()
            }) { Text(stringResource(R.string.action_remove)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Preview(showBackground = true)
@Composable
private fun DownloadsContentPreview() {
    EarLoftTheme {
        DownloadsContent(
            state = DownloadsUiState(
                overview = DownloadOverview(
                    downloadedSongs = 7,
                    pendingSongs = 3,
                    failedSongs = 1,
                    usedBytes = 48_000_000,
                    freeBytes = 21_000_000_000,
                    currentProgress = 0.4f,
                ),
                albums = listOf(
                    DownloadedAlbum(PreviewAlbum, downloadedSongs = 7, pendingSongs = 3, failedSongs = 1, sizeBytes = 48_000_000),
                ),
                isLoading = false,
            ),
            transfers = emptyMap(),
            onBack = {},
            onAlbumClick = {},
            onBookClick = {},
            onPodcastClick = {},
            onRetryFailed = {},
            onRemoveAlbum = {},
            onRemoveBook = {},
            onRemoveFeed = {},
            onRemoveCategory = {},
            onRemoveAll = {},
        )
    }
}
