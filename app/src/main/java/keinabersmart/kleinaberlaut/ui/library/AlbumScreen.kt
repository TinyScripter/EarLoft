package keinabersmart.kleinaberlaut.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.designsystem.theme.KleinaberlautTheme
import keinabersmart.kleinaberlaut.core.model.AlbumDetails
import keinabersmart.kleinaberlaut.core.model.AlbumOfflineStatus
import keinabersmart.kleinaberlaut.core.model.SongOfflineState
import keinabersmart.kleinaberlaut.core.model.SongOfflineStatus
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.ui.LoadState
import keinabersmart.kleinaberlaut.ui.components.AlbumArt
import keinabersmart.kleinaberlaut.ui.components.EmptyHint
import keinabersmart.kleinaberlaut.ui.components.NoSongActions
import keinabersmart.kleinaberlaut.ui.components.PreviewAlbum
import keinabersmart.kleinaberlaut.ui.components.PreviewSong
import keinabersmart.kleinaberlaut.ui.components.SectionHeader
import keinabersmart.kleinaberlaut.ui.components.SongActions
import keinabersmart.kleinaberlaut.ui.components.SongListItem
import keinabersmart.kleinaberlaut.ui.components.WatchStatusText
import keinabersmart.kleinaberlaut.ui.components.WatchToggleButton
import keinabersmart.kleinaberlaut.ui.components.formatDuration
import keinabersmart.kleinaberlaut.ui.playlists.AddToPlaylistDialog
import keinabersmart.kleinaberlaut.core.designsystem.R as DesignR

@Composable
fun AlbumScreen(
    albumId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel<AlbumViewModel, AlbumViewModel.Factory> { it.create(albumId) },
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    AlbumContent(
        state = album,
        offline = offline,
        onBack = onBack,
        onPlay = viewModel::play,
        onShuffle = viewModel::shuffle,
        onDownload = viewModel::downloadAlbum,
        onRemoveDownload = viewModel::removeAlbumDownload,
        actions = viewModel,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AlbumContent(
    state: LoadState<AlbumDetails>,
    offline: AlbumOfflineStatus,
    onBack: () -> Unit,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    actions: SongActions,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { if (state is LoadState.Ready) WatchStatusText(WearSelectionKind.ALBUM, state.value.album.id) },
            navigationIcon = { BackButton(onBack) },
            actions = {
                if (state is LoadState.Ready) {
                    WatchToggleButton(WearSelectionKind.ALBUM, state.value.album.id)
                    AddAlbumToPlaylistButton(state.value.songs.map { it.id })
                }
            },
        )
        when (state) {
            LoadState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            is LoadState.Ready -> AlbumSongs(state.value, offline, onPlay, onShuffle, onDownload, onRemoveDownload, actions)
        }
    }
}

@Composable
private fun AlbumSongs(
    details: AlbumDetails,
    offline: AlbumOfflineStatus,
    onPlay: (Int) -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
    actions: SongActions,
) {
    val songs = details.songs
    val multiDisc = songs.map { it.discNumber }.distinct().size > 1

    LazyColumn(Modifier.fillMaxSize()) {
        item(key = "header") {
            AlbumHeader(
                details = details,
                offline = offline,
                onPlay = { onPlay(0) },
                onShuffle = onShuffle,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
            )
        }
        songs.forEachIndexed { index, song ->
            if (multiDisc && (index == 0 || songs[index - 1].discNumber != song.discNumber)) {
                item(key = "disc-${song.discNumber}") {
                    SectionHeader(stringResource(R.string.disc_number, song.discNumber))
                }
            }
            item(key = song.id) {
                SongListItem(
                    song = song,
                    onClick = { onPlay(index) },
                    actions = actions,
                    trackNumber = song.trackNumber,
                )
            }
        }
    }
}

@Composable
private fun AlbumHeader(
    details: AlbumDetails,
    offline: AlbumOfflineStatus,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val album = details.album
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AlbumArt(albumId = album.id, modifier = Modifier.widthIn(max = 240.dp).fillMaxWidth(), shape = MaterialTheme.shapes.large)
        Spacer(Modifier.height(16.dp))
        Text(album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            album.artist,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
        val totalMs = details.songs.sumOf { it.durationMs }
        val meta = listOfNotNull(
            album.year?.toString(),
            pluralStringResource(R.plurals.song_count, details.songs.size, details.songs.size),
            formatDuration(totalMs),
        ).joinToString(" · ")
        Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_play))
            }
            FilledTonalButton(onClick = onShuffle) {
                Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_shuffle))
            }
            // Rein lokale Alben brauchen keinen Download.
            if (offline.downloadableCount > 0) {
                DownloadButton(
                    removeTitle = stringResource(R.string.download_album_remove_title),
                    removeText = stringResource(R.string.download_album_remove_text, album.title),
                    offline = offline,
                    onDownload = onDownload,
                    onRemoveDownload = onRemoveDownload,
                )
            }
        }
        DownloadStatusLine(offline)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun DownloadButton(
    removeTitle: String,
    removeText: String,
    offline: AlbumOfflineStatus,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    when {
        offline.pendingCount > 0 -> {
            val finished = offline.downloadedCount
            val total = finished + offline.pendingCount
            IconButton(onClick = { confirmRemove = true }) {
                CircularProgressIndicator(
                    progress = { if (total > 0) finished.toFloat() / total else 0f },
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 3.dp,
                )
                Icon(
                    painterResource(DesignR.drawable.ic_close),
                    contentDescription = stringResource(R.string.download_album_running),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
        offline.isFullyOffline -> FilledTonalIconButton(onClick = { confirmRemove = true }) {
            Icon(painterResource(DesignR.drawable.ic_download_done), contentDescription = stringResource(R.string.download_album_done))
        }
        else -> IconButton(onClick = onDownload) {
            Icon(painterResource(DesignR.drawable.ic_download), contentDescription = stringResource(R.string.action_download))
        }
    }

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(removeTitle) },
            text = { Text(removeText) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemoveDownload()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
fun DownloadStatusLine(offline: AlbumOfflineStatus) {
    val text = when {
        offline.pendingCount > 0 -> stringResource(
            R.string.download_album_progress,
            offline.downloadedCount,
            offline.downloadedCount + offline.pendingCount,
        )
        offline.failedCount > 0 -> pluralStringResource(R.plurals.download_album_failed, offline.failedCount, offline.failedCount)
        else -> return
    }
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (offline.pendingCount == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun AddAlbumToPlaylistButton(songIds: List<Long>) {
    var choosing by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { choosing = true }) {
        Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = stringResource(R.string.action_add_to_playlist))
    }
    if (choosing) AddToPlaylistDialog(songIds = songIds, onDismiss = { choosing = false })
}

@Composable
fun BackButton(onBack: () -> Unit) {
    IconButton(onClick = onBack) {
        Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumContentPreview() {
    val songs = List(4) { PreviewSong.copy(id = it.toLong(), trackNumber = it + 1) }
    KleinaberlautTheme {
        AlbumContent(
            state = LoadState.Ready(AlbumDetails(album = PreviewAlbum, songs = songs)),
            offline = AlbumOfflineStatus(
                mapOf(
                    0L to SongOfflineStatus(SongOfflineState.DOWNLOADED),
                    1L to SongOfflineStatus(SongOfflineState.DOWNLOADING, progress = 0.3f),
                    2L to SongOfflineStatus(SongOfflineState.QUEUED),
                    3L to SongOfflineStatus(SongOfflineState.NONE),
                ),
            ),
            onBack = {},
            onPlay = {},
            onShuffle = {},
            onDownload = {},
            onRemoveDownload = {},
            actions = NoSongActions,
        )
    }
}
