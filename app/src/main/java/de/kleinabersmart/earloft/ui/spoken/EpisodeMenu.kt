package de.kleinabersmart.earloft.ui.spoken

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
import de.kleinabersmart.earloft.ui.components.ShareMenuItem
import de.kleinabersmart.earloft.ui.components.WatchMenuItem
import de.kleinabersmart.earloft.ui.components.WatchTarget
import de.kleinabersmart.earloft.ui.snackbar.LocalSnackbars
import de.kleinabersmart.earloft.core.designsystem.R as DesignR

val BookWithProgress.isEpisode: Boolean get() = book.kind == BookKind.EPISODE

/**
 * ⋮ in der Kopfzeile der Buch- bzw. Folgenseite und des Players: dasselbe Menü wie beim Langklick, ohne Teilen und Uhr
 * (die stehen dort schon als Knöpfe daneben).
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
 * Stern einer Folge (Konzept 12.5, „Gemerkt“) im Kopf der Folgenseite und des Players; für Bücher und Folgen von
 * Probeabos nichts.
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

/** Kleiner Stern in einer Folgenzeile: Die Folge ist gemerkt. */
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
 * Kontextmenü einer Podcast-Folge oder eines Buchs (Langklick); die erste Zeile nennt den Titel zur Orientierung. Mit
 * [onSelect] beginnt „Auswählen“ die Mehrfachauswahl der Liste mit dieser Folge (Konzept 12.5).
 */
@Composable
fun EpisodeMenu(
    item: BookWithProgress,
    expanded: Boolean,
    onDismiss: () -> Unit,
    withShareAndWatch: Boolean = true,
    onSelect: (() -> Unit)? = null,
    viewModel: EpisodeMenuViewModel = hiltViewModel(),
) {
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val queueHead by viewModel.queueHead.collectAsStateWithLifecycle()
    val queueSecond by viewModel.queueSecond.collectAsStateWithLifecycle()
    val trialFeeds by viewModel.trialFeeds.collectAsStateWithLifecycle()
    val loadedBookId by viewModel.loadedBookId.collectAsStateWithLifecycle()
    var confirmRemove by remember { mutableStateOf(false) }
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
                viewModel.setQueued(id, !inQueue)
                // Hinten angehängt; bei langer Warteschlange sieht man das sonst nicht.
                if (!inQueue) snackbars.show(resources.getString(R.string.queue_added_at_end, item.book.title))
                onDismiss()
            },
        )
        // Direkt hinter die laufende Folge; nicht für diese selbst und nicht, wenn sie dort schon steht.
        if (item.isEpisode && id != queueHead && id != queueSecond) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_play_next)) },
            onClick = {
                viewModel.playNext(id)
                onDismiss()
            },
        )
        if (item.isEpisode && item.book.serverId !in trialFeeds) DropdownMenuItem(
            text = { Text(stringResource(if (item.book.starred) R.string.episode_unstar else R.string.episode_star)) },
            onClick = {
                viewModel.setStarred(listOf(item), !item.book.starred)
                onDismiss()
            },
        )
        // Bücher in „Weiterhören“: herausnehmen, ohne den Stand zu verlieren (Folgen: „Aus Warteschlange entfernen“).
        val progress = item.progress
        if (!item.isEpisode && progress != null && !progress.finished && !progress.hidden) DropdownMenuItem(
            text = { Text(stringResource(R.string.book_hide_from_continue)) },
            onClick = {
                viewModel.hideFromContinueListening(item)
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
        // Nur, wenn in der Warteschlange noch eine andere Folge steht.
        if (item.isEpisode && !played && (queued - id).isNotEmpty()) DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_mark_played_play_next)) },
            onClick = {
                viewModel.setPlayedAndPlayNext(item)
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
        // Podcasts vom Gerät haben keine öffentliche Adresse; Bücher vom Gerät finden sich über die Suche bei Open Library.
        if (withShareAndWatch) {
            if (!item.isEpisode || !item.book.isLocal) ShareMenuItem(ShareTarget.Book(id), onDismiss, withIcon = false)
            WatchMenuItem(WatchTarget.Spoken(id), onDismiss, withIcon = false)
        }
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
        // Bücher aus ARD Sounds kamen von Hand in die Bibliothek und gehen auch so wieder (nicht, solange sie im Player sind).
        if (item.book.isArdSounds && loadedBookId != id) DropdownMenuItem(
            text = { Text(stringResource(R.string.ard_books_remove)) },
            onClick = {
                confirmRemove = true
                onDismiss()
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.ard_books_remove_title, item.book.title)) },
            text = { Text(stringResource(R.string.ard_books_remove_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    viewModel.removeFromLibrary(item)
                }) { Text(stringResource(R.string.ard_books_remove)) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}
