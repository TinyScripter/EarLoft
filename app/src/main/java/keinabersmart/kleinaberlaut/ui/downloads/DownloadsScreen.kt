package keinabersmart.kleinaberlaut.ui.downloads

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.DownloadOverview
import keinabersmart.kleinaberlaut.core.model.DownloadedAlbum
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.formatBytes
import keinabersmart.kleinaberlaut.ui.library.BackButton
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR
import kotlinx.coroutines.delay

@Composable
fun DownloadsScreen(
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DownloadsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DownloadsContent(
        state = state,
        onBack = onBack,
        onAlbumClick = onAlbumClick,
        onRetryFailed = viewModel::retryFailed,
        onRemoveAlbum = viewModel::removeAlbum,
        onRemoveAll = viewModel::removeAll,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadsContent(
    state: DownloadsUiState,
    onBack: () -> Unit,
    onAlbumClick: (Long) -> Unit,
    onRetryFailed: () -> Unit,
    onRemoveAlbum: (Long) -> Unit,
    onRemoveAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var albumToRemove by rememberSaveable { mutableStateOf<Long?>(null) }
    var confirmRemoveAll by rememberSaveable { mutableStateOf(false) }
    val overview = state.overview

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_downloads)) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (state.albums.isNotEmpty()) {
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
            state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.albums.isEmpty() -> EmptyHint(stringResource(R.string.downloads_empty))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "storage") { StorageLine(overview) }
                if (overview.pendingSongs > 0) item(key = "pending") { PendingRow(overview) }
                if (overview.failedSongs > 0) item(key = "failed") { FailedRow(overview.failedSongs, onRetryFailed) }
                items(state.albums, key = { it.album.id }) { album ->
                    DownloadedAlbumRow(
                        album = album,
                        onClick = { onAlbumClick(album.album.id) },
                        onRemove = { albumToRemove = album.album.id },
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
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
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
    KleinaberlautTheme {
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
            onBack = {},
            onAlbumClick = {},
            onRetryFailed = {},
            onRemoveAlbum = {},
            onRemoveAll = {},
        )
    }
}
