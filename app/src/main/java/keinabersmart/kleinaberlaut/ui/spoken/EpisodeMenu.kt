package keinabersmart.kleinaberlaut.ui.spoken

import android.widget.Toast
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
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
import keinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import keinabersmart.kleinaberlaut.core.data.spoken.BookRepository
import keinabersmart.kleinaberlaut.core.download.BookDownloads
import keinabersmart.kleinaberlaut.core.download.DownloadStart
import keinabersmart.kleinaberlaut.core.download.EpisodeDownloads
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.BookWithProgress
import keinabersmart.kleinaberlaut.core.playback.PlaybackConnection
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    private val books: BookRepository,
    private val bookDownloads: BookDownloads,
    private val progressSync: BookProgressSync,
) : ViewModel() {

    /** Folgen, die gerade in der Zeile „Weiterhören“ (der Warteschlange) stehen. */
    val queued: StateFlow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Folgen und Bücher mit geladenen Dateien oder laufendem bzw. wartendem Download (dann gibt es etwas zu löschen). */
    val downloaded: StateFlow<Set<Long>> = combine(
        podcasts.downloadedIds(),
        books.downloads().map { map -> map.filterValues { it.state != TrackDownload.NONE && it.state != TrackDownload.FAILED }.keys },
    ) { episodes, bookIds -> episodes + bookIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun setQueued(bookId: Long, queued: Boolean) {
        viewModelScope.launch { if (queued) continueListening.append(bookId) else continueListening.remove(bookId) }
    }

    /** Bücher melden den Stand auch an Audiobookshelf, Folgen haben nur den lokalen Stand. */
    fun setPlayed(item: BookWithProgress, played: Boolean) {
        val id = item.book.id
        viewModelScope.launch {
            if (item.isEpisode) {
                podcasts.setPlayed(id, played)
            } else {
                val duration = item.book.durationMs
                progressSync.record(id, if (played) duration else 0, duration, finished = played, force = true)
            }
        }
    }

    fun resetPosition(item: BookWithProgress) {
        val bookId = item.book.id
        // Ein nie gestartetes Buch hat nichts zurückzusetzen; sonst stünde es danach mit 0 % in „Weiterhören“.
        if (!item.isEpisode && item.progress == null) return
        viewModelScope.launch {
            if (item.isEpisode) podcasts.resetPosition(bookId) else progressSync.record(bookId, 0, item.book.durationMs, finished = false, force = true)
            // Läuft die Folge gerade, springt auch die Wiedergabe an den Anfang.
            if (playback.state.value.spoken?.bookId == bookId) playback.seekTo(0)
        }
    }

    fun removeDownload(item: BookWithProgress) {
        viewModelScope.launch { if (item.isEpisode) downloads.remove(item.book.id) else bookDownloads.remove(item.book.id) }
    }

    /** [onHint] bekommt einen Text, wenn der Download nicht gleich beginnt (offline, wartet auf WLAN). */
    fun download(item: BookWithProgress, onHint: (Int) -> Unit) {
        viewModelScope.launch {
            val start = if (item.isEpisode) downloads.download(item.book.id) else bookDownloads.download(item.book.id)
            when (start) {
                DownloadStart.OFFLINE -> onHint(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI -> onHint(R.string.podcast_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }
}

val BookWithProgress.isEpisode: Boolean get() = book.kind == BookKind.EPISODE

/** Kontextmenü einer Podcast-Folge oder eines Buchs (Langklick); die erste Zeile nennt Podcast bzw. Autor zur Orientierung. */
@Composable
fun EpisodeMenu(
    item: BookWithProgress,
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: EpisodeMenuViewModel = hiltViewModel(),
) {
    val queued by viewModel.queued.collectAsStateWithLifecycle()
    val downloaded by viewModel.downloaded.collectAsStateWithLifecycle()
    val context = LocalContext.current
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
        if (item.isEpisode) DropdownMenuItem(
            text = { Text(stringResource(if (inQueue) R.string.episode_queue_remove else R.string.episode_queue_add)) },
            onClick = {
                viewModel.setQueued(id, !inQueue)
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
                        viewModel.download(item) { Toast.makeText(context, it, Toast.LENGTH_LONG).show() }
                        onDismiss()
                    },
                )
            }
        }
    }
}
