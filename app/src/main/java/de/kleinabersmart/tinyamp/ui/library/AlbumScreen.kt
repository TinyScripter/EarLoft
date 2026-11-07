package de.kleinabersmart.tinyamp.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.tinyamp.R
import de.kleinabersmart.tinyamp.core.designsystem.theme.AlbumColorTheme
import de.kleinabersmart.tinyamp.navigation.LocalClosesDetailPane
import de.kleinabersmart.tinyamp.core.designsystem.theme.TinyAmpTheme
import de.kleinabersmart.tinyamp.core.model.AlbumDetails
import de.kleinabersmart.tinyamp.core.model.AlbumOfflineStatus
import de.kleinabersmart.tinyamp.core.model.SongOfflineState
import de.kleinabersmart.tinyamp.core.model.SongOfflineStatus
import de.kleinabersmart.tinyamp.core.model.WearSelectionKind
import de.kleinabersmart.tinyamp.ui.LoadState
import de.kleinabersmart.tinyamp.ui.components.AlbumArt
import de.kleinabersmart.tinyamp.ui.components.EmptyHint
import de.kleinabersmart.tinyamp.ui.components.LoadFailedHint
import de.kleinabersmart.tinyamp.ui.components.LoadingIndicator
import de.kleinabersmart.tinyamp.ui.components.NoSongActions
import de.kleinabersmart.tinyamp.ui.components.PreviewAlbum
import de.kleinabersmart.tinyamp.ui.components.PreviewSong
import de.kleinabersmart.tinyamp.ui.components.GroupedItem
import de.kleinabersmart.tinyamp.ui.components.SectionHeader
import de.kleinabersmart.tinyamp.ui.components.ShareButton
import de.kleinabersmart.tinyamp.core.data.share.ShareTarget
import de.kleinabersmart.tinyamp.ui.components.SongActions
import de.kleinabersmart.tinyamp.ui.components.SongListItem
import de.kleinabersmart.tinyamp.ui.components.TwoColumnMinWidth
import de.kleinabersmart.tinyamp.ui.components.WatchStatusText
import de.kleinabersmart.tinyamp.ui.components.WatchToggleButton
import de.kleinabersmart.tinyamp.ui.components.formatDuration
import de.kleinabersmart.tinyamp.ui.playlists.AddToPlaylistDialog
import de.kleinabersmart.tinyamp.core.designsystem.R as DesignR

@Composable
fun AlbumScreen(
    albumId: Long,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumViewModel = hiltViewModel<AlbumViewModel, AlbumViewModel.Factory> { it.create(albumId) },
) {
    val album by viewModel.album.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val seedColor by viewModel.seedColor.collectAsStateWithLifecycle()
    AlbumColorTheme(seedColor) {
        Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AlbumContent(
                state = album,
                offline = offline,
                onBack = onBack,
                onPlay = viewModel::play,
                onShuffle = viewModel::shuffle,
                onDownload = viewModel::downloadAlbum,
                onRemoveDownload = viewModel::removeAlbumDownload,
                actions = viewModel,
            )
        }
    }
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
                    ShareButton(ShareTarget.Album(state.value.album.id))
                }
            },
        )
        when (state) {
            LoadState.Loading -> LoadingIndicator()
            LoadState.Missing -> EmptyHint(stringResource(R.string.item_missing))
            LoadState.Failed -> LoadFailedHint()
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
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Handy quer: zu niedrig für Kopf über den Songs, daher wie auf breiten Fenstern nebeneinander.
        val short = maxWidth > maxHeight && maxHeight < 480.dp
        // Niedrig (kleines Handy hochkant): Das Cover schrumpft, damit „Abspielen“ ohne Scrollen sichtbar bleibt.
        val coverMax = when {
            short -> 64.dp
            maxHeight < 560.dp -> minOf(240.dp, maxHeight * 0.38f)
            else -> 240.dp
        }
        val header = @Composable {
            AlbumHeader(
                details = details,
                offline = offline,
                coverMax = coverMax,
                compact = short,
                onPlay = { onPlay(0) },
                onShuffle = onShuffle,
                onDownload = onDownload,
                onRemoveDownload = onRemoveDownload,
            )
        }
        if (maxWidth >= TwoColumnMinWidth || short) {
            // Breites Fenster: Cover und Knöpfe links, die Songs rechts daneben.
            val headerWidth = maxWidth * 0.4f
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(headerWidth)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp, bottom = 24.dp),
                ) { header() }
                LazyColumn(Modifier.weight(1f).fillMaxHeight(), contentPadding = PaddingValues(vertical = 8.dp)) {
                    albumSongItems(details, onPlay, actions)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "header") { header() }
                albumSongItems(details, onPlay, actions)
            }
        }
    }
}

private fun LazyListScope.albumSongItems(details: AlbumDetails, onPlay: (Int) -> Unit, actions: SongActions) {
    val songs = details.songs
    val multiDisc = songs.map { it.discNumber }.distinct().size > 1
    // Jede CD (aufeinanderfolgende Songs mit gleicher Nummer) bildet eine Gruppe, bei mehreren CDs mit Überschrift.
    val discs = mutableListOf<IntRange>()
    songs.indices.forEach { index ->
        if (index == 0 || songs[index - 1].discNumber != songs[index].discNumber) {
            discs += index..index
        } else {
            discs[discs.lastIndex] = discs.last().first..index
        }
    }
    discs.forEach { disc ->
        val discNumber = songs[disc.first].discNumber
        if (multiDisc) {
            item(key = "disc-$discNumber") {
                SectionHeader(stringResource(R.string.disc_number, discNumber))
            }
        }
        disc.forEach { index ->
            val song = songs[index]
            item(key = song.id) {
                GroupedItem(index - disc.first, disc.count()) {
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
}

@Composable
private fun AlbumHeader(
    details: AlbumDetails,
    offline: AlbumOfflineStatus,
    coverMax: Dp,
    compact: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onRemoveDownload: () -> Unit,
) {
    val album = details.album
    val totalMs = details.songs.sumOf { it.durationMs }
    val meta = listOfNotNull(
        album.year?.toString(),
        pluralStringResource(R.plurals.song_count, details.songs.size, details.songs.size),
        formatDuration(totalMs),
    ).joinToString(" · ")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = if (compact) 12.dp else 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (compact) {
            // Handy quer: Cover neben dem Titel, damit die Knöpfe in der niedrigen Spalte ohne Scrollen sichtbar bleiben.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AlbumArt(albumId = album.id, modifier = Modifier.size(coverMax), shape = MaterialTheme.shapes.medium)
                Column(Modifier.weight(1f)) {
                    Text(album.title, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(
                        album.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        } else {
            AlbumArt(albumId = album.id, modifier = Modifier.widthIn(max = coverMax).fillMaxWidth(), shape = MaterialTheme.shapes.large)
            Spacer(Modifier.height(16.dp))
            Text(album.title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                album.artist,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = onPlay) {
                Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.action_play))
            }
            if (compact) {
                // Nur das Symbol: Play, Zufall und Download passen sonst nicht in die schmale Spalte.
                FilledTonalIconButton(onClick = onShuffle) {
                    Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = stringResource(R.string.action_shuffle))
                }
            } else {
                FilledTonalButton(onClick = onShuffle) {
                    Icon(painterResource(DesignR.drawable.ic_shuffle), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.action_shuffle))
                }
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
        // Rechts neben der Liste schließt „Zurück“ nur die Spalte – dann ✕ statt Pfeil.
        if (LocalClosesDetailPane.current) {
            Icon(painterResource(DesignR.drawable.ic_close), contentDescription = stringResource(R.string.action_close_pane))
        } else {
            Icon(painterResource(DesignR.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AlbumContentPreview() {
    val songs = List(4) { PreviewSong.copy(id = it.toLong(), trackNumber = it + 1) }
    TinyAmpTheme {
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
