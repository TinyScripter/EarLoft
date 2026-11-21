package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
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

/** Asks before deleting the downloads of all heard episodes of a subscription (menu and subscription settings). */
@Composable
internal fun RemoveHeardDownloadsDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.podcast_remove_heard_downloads)) },
        text = { Text(stringResource(R.string.podcast_remove_heard_downloads_summary)) },
        confirmButton = { DestructiveTextButton(onClick = onConfirm) { Text(stringResource(R.string.book_download_delete_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/**
 * Context menu of a subscription (long press, and ⋮ on its page), built like the one for albums: the title at the top, below it play next episode,
 * add next episode to the queue, refresh, mark all as played (with confirmation), delete downloads of played episodes,
 * share and the subscription's settings. "Unsubscribe" is deliberately only in the settings. Podcasts from the device have
 * no feed and no downloads; those entries are missing there. A trial subscription (only its own page has the ⋮) offers
 * subscribing instead of playing the next episode, "mark all as played" and the settings: its progress is tidied away when the
 * page closes, and the settings belong to a subscription (concept 12.10, review H10). Queueing the next episode and sharing stay,
 * like the queue button on its episodes. Only include it when it is open.
 */
@Composable
fun FeedMenu(
    feed: PodcastFeed,
    onSettings: () -> Unit,
    onDismiss: () -> Unit,
    /** Off on the subscription's own page: its title is right there, and refreshing has its own button. */
    withTitle: Boolean = true,
    withRefresh: Boolean = true,
    /** Offered while [feed] is a trial subscription. */
    onSubscribe: () -> Unit = {},
    viewModel: FeedMenuViewModel = hiltViewModel(),
) {
    val state by remember(feed.id) { viewModel.state(feed.id) }.collectAsStateWithLifecycle(null)
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    var menuOpen by rememberSaveable { mutableStateOf(true) }
    var confirmAllPlayed by rememberSaveable { mutableStateOf(false) }
    var confirmRemoveHeard by rememberSaveable { mutableStateOf(false) }
    val current = state
    val trial = !feed.subscribed

    DropdownMenu(expanded = menuOpen, onDismissRequest = onDismiss) {
        if (withTitle) {
            DropdownMenuItem(
                text = { Text(feed.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                onClick = {},
                enabled = false,
                colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
            )
            HorizontalDivider()
        }
        if (trial) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.podcast_add_confirm)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_add), contentDescription = null) },
                onClick = {
                    onDismiss()
                    onSubscribe()
                },
            )
        }
        current?.next?.let { next ->
            if (!trial) DropdownMenuItem(
                text = {
                    // Which episode it will be is shown below.
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
        if (!feed.isLocal && withRefresh) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.podcast_refresh_feed)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_sync), contentDescription = null) },
                onClick = {
                    viewModel.refresh(feed.id)
                    onDismiss()
                },
            )
        }
        if (!trial && current != null && current.unheard > 0) {
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
                // Touches several episodes at once: asks first (single changes have "Undo" instead).
                onClick = {
                    menuOpen = false
                    confirmRemoveHeard = true
                },
            )
        }
        if (!feed.isLocal) ShareMenuItem(ShareTarget.Feed(feed.id), onDismiss)
        if (!trial) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.podcast_settings)) },
                leadingIcon = { Icon(painterResource(DesignR.drawable.ic_settings), contentDescription = null) },
                onClick = {
                    onDismiss()
                    onSettings()
                },
            )
        }
    }

    if (confirmRemoveHeard) {
        RemoveHeardDownloadsDialog(
            onConfirm = {
                viewModel.removeHeardDownloads(feed.id)
                onDismiss()
            },
            onDismiss = onDismiss,
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
