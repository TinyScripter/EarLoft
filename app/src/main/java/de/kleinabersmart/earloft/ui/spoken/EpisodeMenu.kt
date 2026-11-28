package de.kleinabersmart.earloft.ui.spoken

import de.kleinabersmart.earloft.ui.components.DestructiveTextButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.data.share.ShareTarget
import de.kleinabersmart.earloft.ui.components.ShareMediaMenuItem
import de.kleinabersmart.earloft.ui.formatBytes
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.components.WatchMenuItem
import de.kleinabersmart.earloft.ui.components.WatchTarget
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

val BookWithProgress.isEpisode: Boolean get() = book.kind == BookKind.EPISODE

/**
 * ⋮ in the header of the book or episode page and of the player: the same menu as on long press, without share and watch
 * (they are already there as buttons next to it).
 */
@Composable
fun EpisodeMenuButton(item: BookWithProgress) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(DesignR.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
        }
        EpisodeMenu(item, expanded, onDismiss = { expanded = false }, withShareAndWatch = false)
    }
}

/**
 * Star of an episode (concept 12.5, "Starred") in the header of the episode page and the player; nothing for books and episodes of
 * trial subscriptions.
 */
@Composable
fun EpisodeStarButton(item: BookWithProgress, viewModel: EpisodeMenuViewModel = hiltViewModel()) {
    val trialFeeds by viewModel.trialFeeds.collectAsStateWithLifecycle()
    if (!item.isEpisode || item.book.serverId in trialFeeds) return
    val starred = item.book.starred
    IconButton(onClick = { viewModel.setStarred(listOf(item), !starred) }) {
        Icon(
            painterResource(if (starred) DesignR.drawable.ic_star else DesignR.drawable.ic_star_border),
            contentDescription = stringResource(if (starred) R.string.episode_unstar else R.string.episode_star),
            tint = if (starred) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}

/** Small star in an episode row: the episode is starred. */
@Composable
fun StarredMark(modifier: Modifier = Modifier) {
    Icon(
        painterResource(DesignR.drawable.ic_star),
        contentDescription = stringResource(R.string.episode_starred),
        tint = MaterialTheme.colorScheme.primary,
        modifier = modifier.size(16.dp),
    )
}

/**
 * Context menu of a podcast episode or a book (long press); the first row names the title for orientation. With
 * [onSelect], "Select" starts the list's multi-selection with this episode (concept 12.5). [inRecent]: the tile stands in
 * "Recently added" on the podcast Home, which offers to remove it from there.
 *
 * Every tile and row has one, so a closed menu without an open question composes nothing: otherwise each of them collected seven to
 * ten flows while scrolling a large library (review4 T45). The questions survive a rotation (T44).
 */
@Composable
fun EpisodeMenu(
    item: BookWithProgress,
    expanded: Boolean,
    onDismiss: () -> Unit,
    withShareAndWatch: Boolean = true,
    onSelect: (() -> Unit)? = null,
    inRecent: Boolean = false,
    viewModel: EpisodeMenuViewModel? = null,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var confirmDeleteDownload by rememberSaveable { mutableStateOf(false) }
    if (!expanded && !confirmRemove && !confirmDeleteDownload) return
    val viewModel = viewModel ?: hiltViewModel<EpisodeMenuViewModel>()
    // Opens once everything is known, not with defaults for a moment (review4 Y20).
    val facts by viewModel.facts.collectAsStateWithLifecycle()
    val known = facts
    val (queued, downloaded, downloadedEpisodes, queueHead, queueSecond, trialFeeds, loadedBookId) = known ?: NO_FACTS
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val id = item.book.id
    val inQueue = id in queued
    val played = item.progress?.finished == true
    // An episode of a trial subscription keeps its progress only while it is in the queue: "played", "unplayed" and "reset"
    // would be forgotten right away, as with "Mark all as played" (H10, review2 J4).
    val trial = item.isEpisode && item.book.serverId in trialFeeds
    val undoLabel = stringResource(R.string.action_undo)
    // Single changes happen right away and can be undone from the snackbar (as when swiping); only "all" asks first.
    val offerUndo: (Int) -> (suspend () -> Unit) -> Unit = { text ->
        { restore ->
            snackbars.show(resources.getString(text, item.book.title), SnackbarDuration.Long, undoLabel) { viewModel.undo(restore) }
        }
    }

    DropdownMenu(expanded = expanded && known != null, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        if (onSelect != null) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_select)) },
            onClick = {
                onSelect()
                onDismiss()
            },
        )
        if (item.isEpisode) DropdownMenuItem(
            text = { Text(stringResource(if (inQueue) R.string.episode_queue_remove else R.string.episode_queue_add)) },
            onClick = {
                // A trial episode loses download and progress right after leaving the queue: no undo (review3 Q24).
                val onRemoved: (suspend () -> Unit) -> Unit = if (trial) {
                    { _ -> snackbars.show(resources.getString(R.string.queue_removed, item.book.title)) }
                } else {
                    offerUndo(R.string.queue_removed)
                }
                viewModel.setQueued(id, !inQueue, onRemoved)
                // Appended at the end; with a long queue you wouldn't see it otherwise.
                if (!inQueue) snackbars.show(resources.getString(R.string.queue_added_at_end, item.book.title))
                onDismiss()
            },
        )
        // Directly after the current episode; not for that one itself and not if it is already there.
        if (item.isEpisode && id != queueHead && id != queueSecond) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_play_next)) },
            onClick = {
                viewModel.playNext(id)
                onDismiss()
            },
        )
        if (item.isEpisode && !trial) DropdownMenuItem(
            text = { Text(stringResource(if (item.book.starred) R.string.episode_unstar else R.string.episode_star)) },
            onClick = {
                viewModel.setStarred(listOf(item), !item.book.starred)
                onDismiss()
            },
        )
        if (inRecent && item.isEpisode) DropdownMenuItem(
            text = { Text(stringResource(R.string.recent_remove)) },
            onClick = {
                viewModel.hideFromRecent(id, offerUndo(R.string.recent_removed))
                onDismiss()
            },
        )
        // Books in "Continue listening": remove without losing the progress (episodes: "Remove from queue").
        // Not for the currently loaded book: playback writes it back on the next save tick (review5 P7).
        val progress = item.progress
        if (canHideBookFromContinueListening(item, loadedBookId)) DropdownMenuItem(
            text = { Text(stringResource(R.string.book_hide_from_continue)) },
            onClick = {
                viewModel.hideFromContinueListening(item)
                onDismiss()
            },
        )
        if (!trial) DropdownMenuItem(
            text = { Text(stringResource(if (played) R.string.episode_mark_unplayed else R.string.episode_mark_played)) },
            onClick = {
                viewModel.setPlayed(item, !played, offerUndo(if (played) R.string.episode_marked_unplayed else R.string.episode_marked_played))
                onDismiss()
            },
        )
        // Only if another episode is still in the queue.
        if (item.isEpisode && !trial && !played && (queued - id).isNotEmpty()) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_mark_played_play_next)) },
            onClick = {
                viewModel.setPlayedAndPlayNext(item)
                onDismiss()
            },
        )
        // Only with something to reset: on a book never started it did nothing (review4 T43).
        if (!trial && progress != null && (progress.positionMs > 0 || progress.finished)) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_reset_position)) },
            onClick = {
                viewModel.resetPosition(item, offerUndo(R.string.episode_position_reset))
                onDismiss()
            },
        )
        // Like the other entries, without an icon.
        // Podcasts from the device have no public address; books from the device can be found via the Open Library search.
        if (withShareAndWatch) {
            if (!item.isEpisode || !item.book.isLocal) ShareMenuItem(ShareTarget.Book(id), onDismiss, withIcon = false)
            WatchMenuItem(WatchTarget.Spoken(id), onDismiss, withIcon = false)
        }
        // The fully downloaded audio file instead of the link; episodes from the device are already with the user.
        if (item.isEpisode && !item.book.isLocal && id in downloadedEpisodes) ShareMediaMenuItem(id, item.book.title, onDismiss)
        // Files from the device are already there.
        if (!item.book.isLocal) {
            if (id in downloaded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.episode_delete_download)) },
                    onClick = {
                        // A book is often several gigabytes: ask with its size, as the book page does (review G61).
                        if (item.isEpisode) viewModel.removeDownload(item) else confirmDeleteDownload = true
                        onDismiss()
                    },
                )
            } else {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_download)) },
                    onClick = {
                        viewModel.download(item) { snackbars.show(resources.getString(it), SnackbarDuration.Long) }
                        onDismiss()
                    },
                )
            }
        }
        // Books from ARD Sounds were added to the library by hand and leave it the same way (not while they are in the player).
        if (item.book.isArdSounds && loadedBookId != id) DropdownMenuItem(
            text = { Text(stringResource(R.string.ard_books_remove)) },
            onClick = {
                confirmRemove = true
                onDismiss()
            },
        )
    }
    if (confirmDeleteDownload) {
        val download by remember(id) { viewModel.bookDownload(id) }.collectAsStateWithLifecycle(null)
        AlertDialog(
            onDismissRequest = { confirmDeleteDownload = false },
            title = { Text(stringResource(R.string.book_download_delete_title)) },
            text = { Text(stringResource(R.string.book_download_delete_message, formatBytes(download?.bytes ?: 0))) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    confirmDeleteDownload = false
                    viewModel.removeDownload(item)
                }) { Text(stringResource(R.string.book_download_delete_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmDeleteDownload = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.ard_books_remove_title, item.book.title)) },
            text = { Text(stringResource(R.string.ard_books_remove_message)) },
            confirmButton = {
                DestructiveTextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeFromLibrary(item)
                }) { Text(stringResource(R.string.ard_books_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

/** A book in "Continue listening" can be hidden only while it isn't the currently loaded book (review5 P7). */
internal fun canHideBookFromContinueListening(item: BookWithProgress, loadedBookId: Long?): Boolean {
    val progress = item.progress
    return !item.isEpisode && progress != null && !progress.finished && !progress.hidden && loadedBookId != item.book.id
}

private val NO_FACTS = EpisodeMenuFacts(emptySet(), emptySet(), emptySet(), null, null, emptySet(), null)
