package de.kleinabersmart.earloft.ui.spoken

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.kleinabersmart.earloft.R
import de.kleinabersmart.earloft.core.data.podcast.ContinueListening
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.download.BookDownloads
import de.kleinabersmart.earloft.core.download.DownloadStart
import de.kleinabersmart.earloft.core.download.EpisodeDownloads
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.BookWithProgress
import de.kleinabersmart.earloft.core.playback.PlaybackConnection
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

    /**
     * Bücher melden den Stand auch an Audiobookshelf, Folgen haben nur den lokalen Stand. Ein Buch „ungehört“ geht aus
     * „Weiterhören“ zurück in „Zuletzt hinzugefügt“.
     */
    fun setPlayed(item: BookWithProgress, played: Boolean) {
        val id = item.book.id
        viewModelScope.launch {
            if (item.isEpisode) {
                podcasts.setPlayed(id, played)
            } else {
                val duration = item.book.durationMs
                progressSync.record(id, if (played) duration else 0, duration, finished = played, force = true, hidden = !played)
            }
        }
    }

    fun resetPosition(item: BookWithProgress) {
        val bookId = item.book.id
        // Ein nie gestartetes Buch hat nichts zurückzusetzen. Ein zurückgesetztes geht wie „ungehört“ aus „Weiterhören“.
        if (!item.isEpisode && item.progress == null) return
        viewModelScope.launch {
            if (item.isEpisode) {
                podcasts.resetPosition(bookId)
            } else {
                progressSync.record(bookId, 0, item.book.durationMs, finished = false, force = true, hidden = true)
            }
            // Läuft die Folge gerade, springt auch die Wiedergabe an den Anfang.
            if (playback.state.value.spoken?.bookId == bookId) playback.seekTo(0)
        }
    }

    /** „Aus Weiterhören entfernen“ (nur Bücher): Der Stand bleibt; bei Audiobookshelf auf allen Geräten. */
    fun hideFromContinueListening(item: BookWithProgress) {
        viewModelScope.launch { progressSync.hideFromContinueListening(item.book.id) }
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
                DownloadStart.WAITING_FOR_WIFI ->
                    onHint(if (item.isEpisode) R.string.podcast_download_waiting_wifi else R.string.book_download_waiting_wifi)
                DownloadStart.STARTED -> Unit
            }
        }
    }
}
