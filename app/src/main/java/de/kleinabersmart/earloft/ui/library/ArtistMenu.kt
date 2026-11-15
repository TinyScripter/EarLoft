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
 * Kontextmenü eines Künstlers (Langklick auf Kachel oder Zeile, ⋮ auf der Künstlerseite), aufgebaut wie das der Alben: oben der
 * Name, darunter Als Nächstes spielen, Zur Warteschlange (alle seine Songs), Mix starten, Zu Favoriten, Teilen, Herunterladen bzw. Download entfernen
 * (seine Alben). Erst einbinden, wenn es offen ist.
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
        CollectionFavoriteMenuItem(FavoriteKind.ARTIST, artistId, onDismiss)
        ShareMenuItem(ShareTarget.Artist(artistId), onDismiss)
        // Wie bei den Alben: rein lokale Künstler brauchen keinen Download; teilweise geladen bietet beides.
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

    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.download_artist_remove_title)) },
            text = { Text(stringResource(R.string.download_artist_remove_text, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.removeDownload(artistId)
                    onDismiss()
                }) { Text(stringResource(R.string.action_remove)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
