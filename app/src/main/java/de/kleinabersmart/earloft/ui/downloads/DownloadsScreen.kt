package de.kleinabersmart.earloft.ui.downloads

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
import de.kleinabersmart.earloft.ui.components.group
import de.kleinabersmart.earloft.ui.components.groupedItems
import de.kleinabersmart.earloft.ui.components.readableWidth
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.designsystem.theme.EarLoftTheme
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
            isEmpty -> EmptyHint(stringResource(R.string.downloads_empty))
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "storage") { StorageLine(overview) }
                group("status") {
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
                        // Ein wartender oder ladender Download wird ohne Rückfrage abgebrochen, ein geladener gelöscht.
                        onRemove = {
                            if (item.download.state == TrackDownload.DONE) bookToRemove = item.book.id else onRemoveBook(item.book.id)
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
            text = stringResource(R.string.downloads_remove_all_text),
            onConfirm = onRemoveAll,
            onDismiss = { confirmRemoveAll = false },
        )
    }
}

@Composable
private fun StorageLine(overview: DownloadOverview) {
    Text(
        stringResource(R.string.downloads_storage, formatBytes(overview.usedBytes), formatBytes(overview.freeBytes)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = GroupMargin + 16.dp, vertical = 8.dp),
    )
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
            // Zwischen zwei Liedern ist der Fortschritt kurz null. Der Balken bleibt, und der (höhere)
            // Hinweis erscheint erst nach einer Pause, sonst springt die Liste bei jedem fertigen Lied.
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
                    // Kein Download läuft: wartet auf Netz (bzw. WLAN) oder Speicher.
                    Text(stringResource(R.string.downloads_waiting_hint), Modifier.padding(top = 8.dp))
                }
            }
        },
        leadingContent = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
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
    ListItem(
        headlineContent = { Text(album.album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(album.album.artist, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(
                        R.string.downloads_album_summary,
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
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/**
 * Kategorie-Überschrift mit kleinem rotem Mülleimer rechts. Rand und Tippfläche entsprechen dem Mülleimer der
 * Zeilen (ListItem: 16 dp Rand, 48 dp Knopf), damit beide untereinander auf einer Linie liegen.
 */
@Composable
private fun CategoryHeader(text: String, onRemoveAll: () -> Unit) {
    Row(
        // Der Mülleimer steht bündig mit denen der Zeilen in der Gruppe darunter.
        Modifier.fillMaxWidth().padding(start = GroupMargin + 16.dp, end = GroupMargin + 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
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

/** Podcast-Abo mit Bild und der Summe seiner geladenen Folgen (ein Eintrag je Abo); Tipp öffnet den Podcast. */
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

/** Gruppen der Download-Übersicht, die sich als Ganzes löschen lassen. */
enum class DownloadCategory { MUSIC, BOOKS, PODCASTS }

/** Buch mit Cover und Stand: Größe, „32 MB von 500 MB geladen“, Wartet oder Fehler; Tipp öffnet die Buchseite. */
@Composable
private fun DownloadedBookRow(item: DownloadedBook, transfer: BookTransfer?, onClick: () -> Unit, onRemove: () -> Unit) {
    val state = item.download.state
    ListItem(
        headlineContent = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = {
            Column {
                Text(item.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when (state) {
                    TrackDownload.DONE -> Text(formatBytes(item.download.bytes), style = MaterialTheme.typography.bodySmall)
                    TrackDownload.FAILED -> Text(
                        stringResource(R.string.book_download_failed),
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
            TextButton(onClick = {
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
