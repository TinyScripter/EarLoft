package de.kleinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.kleinabersmart.kleinaberlaut.R
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.BookWithProgress
import de.kleinabersmart.kleinaberlaut.ui.components.WatchMenuItem
import de.kleinabersmart.kleinaberlaut.ui.components.WatchTarget
import de.kleinabersmart.kleinaberlaut.ui.snackbar.LocalSnackbars

val BookWithProgress.isEpisode: Boolean get() = book.kind == BookKind.EPISODE

/** Kontextmenü einer Podcast-Folge oder eines Buchs (Langklick); die erste Zeile nennt den Titel zur Orientierung. */
@Composable
fun EpisodeMenu(
    item: BookWithProgress,
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: EpisodeMenuViewModel = hiltViewModel(),
) {
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val snackbars = LocalSnackbars.current
    val resources = LocalResources.current
    val id = item.book.id
    val inQueue = id in queued
    val played = item.progress?.finished == true

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(item.book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        if (item.isEpisode) DropdownMenuItem(
            text = { Text(stringResource(if (inQueue) R.string.episode_queue_remove else R.string.episode_queue_add)) },
            onClick = {
                viewModel.setQueued(id, !inQueue)
                // Hinten angehängt; bei langer Warteschlange sieht man das sonst nicht.
                if (!inQueue) snackbars.show(resources.getString(R.string.queue_added_at_end, item.book.title))
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (played) R.string.episode_mark_unplayed else R.string.episode_mark_played)) },
            onClick = {
                viewModel.setPlayed(item, !played)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_reset_position)) },
            onClick = {
                viewModel.resetPosition(item)
                onDismiss()
            },
        )
        // Wie die übrigen Einträge ohne Symbol.
        WatchMenuItem(WatchTarget.Spoken(id), onDismiss, withIcon = false)
        // Dateien vom Gerät sind schon da.
        if (!item.book.isLocal) {
            if (id in downloaded) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.episode_delete_download)) },
                    onClick = {
                        viewModel.removeDownload(item)
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
    }
}
