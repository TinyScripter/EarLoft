package keinabersmart.kleinaberlaut.ui.spoken

import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import keinabersmart.kleinaberlaut.R
import keinabersmart.kleinaberlaut.core.data.podcast.ContinueListening
import keinabersmart.kleinaberlaut.core.data.podcast.PodcastRepository
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class EpisodeMenuViewModel @Inject constructor(
    private val podcasts: PodcastRepository,
    private val continueListening: ContinueListening,
    private val downloads: EpisodeDownloads,
    private val playback: PlaybackConnection,
) : ViewModel() {

    /** Folgen, die gerade in der Zeile „Weiterhören“ (der Warteschlange) stehen. */
    val queued: StateFlow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val downloaded: StateFlow<Set<Long>> = podcasts.downloadedIds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setQueued(bookId: Long, queued: Boolean) {
        viewModelScope.launch { if (queued) continueListening.append(bookId) else continueListening.remove(bookId) }
    }

    fun setPlayed(bookId: Long, played: Boolean) {
        viewModelScope.launch { podcasts.setPlayed(bookId, played) }
    }

    fun resetPosition(bookId: Long) {
        viewModelScope.launch {
            podcasts.resetPosition(bookId)
            // Läuft die Folge gerade, springt auch die Wiedergabe an den Anfang.
            if (playback.state.value.spoken?.bookId == bookId) playback.seekTo(0)
        }
    }

    fun removeDownload(bookId: Long) {
        viewModelScope.launch { downloads.remove(bookId) }
    }
}

/** Gilt nur für Podcast-Folgen; Hörbücher haben kein Kontextmenü. */
val BookWithProgress.isEpisode: Boolean get() = book.kind == BookKind.EPISODE

/** Kontextmenü einer Podcast-Folge (Langklick); die erste Zeile nennt den Podcast zur Orientierung. */
@Composable
fun EpisodeMenu(
    item: BookWithProgress,
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: EpisodeMenuViewModel = hiltViewModel(),
) {
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val id = item.book.id
    val inQueue = id in queued
    val played = item.progress?.finished == true

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // Der Podcast-Titel steht als Folgen-Autor in der Datenbank.
        DropdownMenuItem(
            text = { Text(item.book.author, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            onClick = {},
            enabled = false,
            colors = MenuDefaults.itemColors(disabledTextColor = MaterialTheme.colorScheme.primary),
        )
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(if (inQueue) R.string.episode_queue_remove else R.string.episode_queue_add)) },
            onClick = {
                viewModel.setQueued(id, !inQueue)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(if (played) R.string.episode_mark_unplayed else R.string.episode_mark_played)) },
            onClick = {
                viewModel.setPlayed(id, !played)
                onDismiss()
            },
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.episode_reset_position)) },
            onClick = {
                viewModel.resetPosition(id)
                onDismiss()
            },
        )
        if (id in downloaded) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.episode_delete_download)) },
                onClick = {
                    viewModel.removeDownload(id)
                    onDismiss()
                },
            )
        }
    }
}
