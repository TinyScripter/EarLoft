package de.kleinabersmart.earloft.ui.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.Album
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.components.WatchMenuItem
import de.kleinabersmart.earloft.ui.components.WatchTarget
import de.kleinabersmart.earloft.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.launch
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Kontextmenü eines Albums (Langklick), aufgebaut wie das der Folgen: oben der Titel, darunter Als Nächstes spielen,
 * Zur Warteschlange, Zur Playlist, Teilen, Herunterladen, Auf Uhr laden bzw. Von Uhr entfernen, Download entfernen. Erst einbinden, wenn es offen ist; [onDismiss]
 * schließt es (auch nach den Dialogen für Playlist und Entfernen).
 */
@Composable
fun AlbumMenu(
    album: Album,
    onDismiss: () -> Unit,
    viewModel: AlbumMenuViewModel = hiltViewModel(),
) {
    val offline by remember(album.id) { viewModel.offline(album.id) }.collectAsStateWithLifecycle(AlbumOfflineStatus.EMPTY)
    val scope = rememberCoroutineScope()
    var menuOpen by rememberSaveable { mutableStateOf(true) }
    var playlistSongs by remember { mutableStateOf<List<Long>?>(null) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_play_next)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_play), contentDescription = null) },
            onClick = {
                viewModel.playNext(album.id)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_queue)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
            onClick = {
                viewModel.addToQueue(album.id)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_playlist)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = null) },
            onClick = {
                menuOpen = false
                scope.launch { playlistSongs = viewModel.songIds(album.id) }
            },
        )
        ShareMenuItem(ShareTarget.Album(album.id), onDismiss)
        // Wie auf der Album-Seite: rein lokale Alben brauchen keinen Download; ein teilweise geladenes Album bietet beides.
        if (offline.downloadableCount > 0) {
            if (!offline.isFullyOffline && offline.pendingCount == 0) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_download)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                    onClick = {
                        viewModel.download(album.id)
                        onDismiss()
                    },
                )
            }
        }
        WatchMenuItem(WatchTarget.Selection(WearSelectionKind.ALBUM, album.id), onDismiss)
        if (offline.downloadableCount > 0) {
            if (offline.hasDownloads) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_download)) },
                    leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                    onClick = {
                        menuOpen = false
                        confirmRemove = true
                    },
                )
            }
        }
    }

    playlistSongs?.let { songIds ->
        if (songIds.isEmpty()) onDismiss() else AddToPlaylistDialog(songIds = songIds, onDismiss = onDismiss)
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.download_album_remove_title)) },
            text = { Text(stringResource(R.string.download_album_remove_text, album.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDownload(album.id)
                    onDismiss()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
