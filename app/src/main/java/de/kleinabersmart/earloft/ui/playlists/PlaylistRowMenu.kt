package de.kleinabersmart.earloft.ui.playlists

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.repository.PlaylistRepository
import de.kleinabersmart.earloft.core.model.Playlist
import de.kleinabersmart.earloft.core.model.Song
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
import de.kleinabersmart.earloft.ui.components.WatchMenuItem
import de.kleinabersmart.earloft.ui.components.WatchTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Context menu of a playlist in the library (long press), built like the album menu: the name at the top, below it Play next,
 * Add to queue (not for an empty one), Load onto watch, Rename and Delete (with confirmation). Only include it while it is open.
 */
@Composable
fun PlaylistRowMenu(
    playlist: Playlist,
    onDismiss: () -> Unit,
    viewModel: PlaylistMenuViewModel = hiltViewModel(),
) {
    var menuOpen by rememberSaveable { mutableStateOf(true) }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        if (playlist.songCount > 0) {
            PlaylistQueueMenuItems(
                onPlayNext = {
                    viewModel.playNext(playlist.id)
                    onDismiss()
                },
                onAddToQueue = {
                    viewModel.addToQueue(playlist.id)
                    onDismiss()
                },
            )
        }
        WatchMenuItem(WatchTarget.Selection(WearSelectionKind.PLAYLIST, playlist.id), onDismiss)
        PlaylistEditMenuItems(
            onRename = {
                menuOpen = false
                renaming = true
            },
            onDelete = {
                menuOpen = false
                confirmDelete = true
            },
        )
    }

    if (renaming) {
        PlaylistNameDialog(
            title = stringResource(R.string.playlist_rename),
            confirmLabel = stringResource(R.string.playlist_save),
            initialName = playlist.name,
            onConfirm = { name, _ ->
                viewModel.rename(playlist.id, name)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
    if (confirmDelete) {
        PlaylistDeleteDialog(
            playlist,
            onConfirm = {
                viewModel.delete(playlist.id)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    }
}

/** "Play next" and "Add to queue" for a whole playlist (row menu and the ⋮ of the playlist page). */
@Composable
internal fun PlaylistQueueMenuItems(onPlayNext: () -> Unit, onAddToQueue: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_play_next)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_play), contentDescription = null) },
        onClick = onPlayNext,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.action_add_to_queue)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
        onClick = onAddToQueue,
    )
}

/** "Rename" and "Delete" of a playlist (row menu and the ⋮ of the playlist page). */
@Composable
internal fun PlaylistEditMenuItems(onRename: () -> Unit, onDelete: () -> Unit) {
    DropdownMenuItem(
        text = { Text(stringResource(R.string.playlist_rename)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_edit), contentDescription = null) },
        onClick = onRename,
    )
    DropdownMenuItem(
        text = { Text(stringResource(R.string.playlist_delete)) },
        leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
        onClick = onDelete,
    )
}

/** Asks before deleting [playlist]; a server playlist is deleted on the server too, which the text says. */
@Composable
internal fun PlaylistDeleteDialog(playlist: Playlist, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.playlist_delete_title)) },
        text = {
            val server = playlist.serverName
            Text(
                if (server != null) {
                    stringResource(R.string.playlist_delete_text_server, playlist.name, server)
                } else {
                    stringResource(R.string.playlist_delete_text, playlist.name)
                },
            )
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.playlist_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@HiltViewModel
class PlaylistMenuViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val playbackConnection: PlaybackConnection,
) : ViewModel() {

    fun playNext(playlistId: Long) = withSongs(playlistId, playbackConnection::playNext)

    fun addToQueue(playlistId: Long) = withSongs(playlistId, playbackConnection::addToQueue)

    fun rename(playlistId: Long, name: String) {
        viewModelScope.launch { playlistRepository.rename(playlistId, name) }
    }

    fun delete(playlistId: Long) {
        viewModelScope.launch { playlistRepository.delete(playlistId) }
    }

    // viewModelScope runs on the main thread, as PlaybackConnection requires.
    private fun withSongs(playlistId: Long, action: (List<Song>) -> Unit) {
        viewModelScope.launch {
            playlistRepository.playlist(playlistId).first()?.entries?.map { it.song }?.takeIf { it.isNotEmpty() }?.let(action)
        }
    }
}
