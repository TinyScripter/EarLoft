package de.kleinabersmart.earloft.ui.library

import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import de.kleinabersmart.earloft.ui.playlists.AddToPlaylistDialog
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.mix.MixSeed
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.model.AlbumOfflineStatus
import de.kleinabersmart.earloft.ui.components.MixMenuItem
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.components.CollectionFavoriteMenuItem
import de.kleinabersmart.earloft.core.model.FavoriteKind
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Context menu of an artist (long press on tile or row, ⋮ on the artist page), structured like the album menu: the
 * name at the top, below it Play next, Add to queue (all their songs), Start mix, Add to favorites, Add to playlist, Share, Download or Remove download
 * (their albums). Only include it while it is open.
 */
@Composable
fun ArtistMenu(
    artistId: Long,
    name: String,
    onDismiss: () -> Unit,
    viewModel: ArtistMenuViewModel = hiltViewModel(),
) {
    val offline by remember(artistId) { viewModel.offline(artistId) }.collectAsStateWithLifecycle(AlbumOfflineStatus.EMPTY)
    var menuOpen by rememberSaveable { mutableStateOf(true) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var playlistSongs by remember { mutableStateOf<List<Long>?>(null) }

    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_play_next)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_play), contentDescription = null) },
            onClick = {
                viewModel.playNext(artistId)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_queue)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
            onClick = {
                viewModel.addToQueue(artistId)
                onDismiss()
            },
        )
        MixMenuItem(MixSeed.Artist(artistId), onDismiss)
        CollectionFavoriteMenuItem(FavoriteKind.ARTIST, artistId, name, onDismiss)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.action_add_to_playlist)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_queue_music), contentDescription = null) },
            onClick = {
                menuOpen = false
                scope.launch { playlistSongs = viewModel.songIds(artistId) }
            },
        )
        ShareMenuItem(ShareTarget.Artist(artistId), onDismiss)
        // As with albums: purely local artists need no download; partially downloaded offers both.
        if (offline.downloadableCount > 0 && !offline.isFullyOffline && offline.pendingCount == 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_download_artist)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_download), contentDescription = null) },
                onClick = {
                    viewModel.download(artistId)
                    onDismiss()
                },
            )
        }
        if (offline.downloadableCount > 0 && offline.hasDownloads) {
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

    playlistSongs?.let { songIds ->
        // Not during composition: closing changes state (review2 J74).
        if (songIds.isEmpty()) LaunchedEffect(songIds) { onDismiss() } else AddToPlaylistDialog(songIds = songIds, onDismiss = onDismiss)
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.download_artist_remove_title)) },
            text = { Text(stringResource(R.string.download_artist_remove_text, name)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    viewModel.removeDownload(artistId)
                    onDismiss()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
