package de.kleinabersmart.earloft.ui.spoken

import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.core.model.PodcastFeed
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

/**
 * Kontextmenü eines Abos (Langklick), aufgebaut wie das der Alben: oben der Titel, darunter Nächste Folge abspielen,
 * Nächste Folge in die Warteschlange, Aktualisieren, Alle als gehört markieren (mit Rückfrage), Downloads gehörter Folgen
 * löschen, Teilen und die Einstellungen des Abos. „Abo beenden“ steht bewusst nur in den Einstellungen. Podcasts vom Gerät haben
 * keinen Feed und keine Downloads; diese Einträge fehlen dort. Erst einbinden, wenn es offen ist.
 */
@Composable
fun FeedMenu(
    feed: PodcastFeed,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: FeedMenuViewModel = hiltViewModel(),
) {
    val state by remember(feed.id) { viewModel.state(feed.id) }.collectAsStateWithLifecycle(null)
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    var menuOpen by rememberSaveable { mutableStateOf(true) }
    var confirmAllPlayed by rememberSaveable { mutableStateOf(false) }
    val current = state

    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        current?.next?.let { next ->
            DropdownMenuItem(
                text = {
                    // Welche Folge es wird, steht darunter.
                    Column {
                        Text(stringResource(R.string.feed_menu_play_next))
                        Text(
                            next.book.title,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_play), contentDescription = null) },
                onClick = {
                    viewModel.play(next.book.id)
                    onDismiss()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feed_menu_queue_next)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_playlist_add), contentDescription = null) },
                onClick = {
                    val message = if (current.nextQueued) R.string.feed_menu_already_queued else R.string.queue_added_at_end
                    if (!current.nextQueued) viewModel.queue(next.book.id)
                    snackbars.show(resources.getString(message, next.book.title))
                    onDismiss()
                },
            )
        }
        if (!feed.isLocal) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.podcast_refresh_feed)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = null) },
                onClick = {
                    viewModel.refresh(feed.id)
                    onDismiss()
                },
            )
        }
        if (current != null && current.unheard > 0) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.feed_menu_mark_all_played)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_done_all), contentDescription = null) },
                onClick = {
                    menuOpen = false
                    confirmAllPlayed = true
                },
            )
        }
        if (!feed.isLocal && current?.hasHeardDownloads == true) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.podcast_remove_heard_downloads)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_delete), contentDescription = null) },
                onClick = {
                    viewModel.removeHeardDownloads(feed.id)
                    onDismiss()
                },
            )
        }
        if (!feed.isLocal) ShareMenuItem(ShareTarget.Feed(feed.id), onDismiss)
        DropdownMenuItem(
            text = { Text(stringResource(R.string.podcast_settings)) },
            leadingIcon = { Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = null) },
            onClick = {
                onDismiss()
                onSettings()
            },
        )
    }

    if (confirmAllPlayed && current != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.feed_menu_mark_all_title)) },
            text = { Text(pluralStringResource(R.plurals.feed_menu_mark_all_text, current.unheard, current.unheard, feed.title)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.markAllPlayed(feed.id)
                    onDismiss()
                }) { Text(stringResource(R.string.feed_menu_mark_all_confirm)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
