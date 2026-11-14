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
import de.kleinabersmart.earloft.core.playback.ServicePlayer
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
    private val servicePlayer: ServicePlayer,
    private val launcher: BookLauncher,
) : ViewModel() {

    /** Folgen, die gerade in der Zeile „Weiterhören“ (der Warteschlange) stehen. */
    val queued: StateFlow<Set<Long>> = continueListening.episodes
        .map { list -> list.mapTo(HashSet()) { it.book.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /**
     * Folgen und Bücher mit geladenen Dateien oder laufendem bzw. wartendem Download (dann gibt es etwas zu löschen); auch
     * teilweise geladene Bücher, deren Zustand nach einer gescheiterten Datei „fehlgeschlagen“ ist.
     */
    val downloaded: StateFlow<Set<Long>> = combine(
        podcasts.downloadedIds(),
        books.downloads().map { map ->
            map.filterValues { it.state != TrackDownload.NONE && (it.state != TrackDownload.FAILED || it.partial) }.keys
        },
    ) { episodes, bookIds -> episodes + bookIds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Die vorderste Folge der Zeile (die laufende bzw. die, mit der es weitergeht); hinter sie kommt „Als Nächstes“. */
    val queueHead: StateFlow<Long?> = continueListening.episodes
        .map { it.firstOrNull()?.book?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Ist die Folge schon die zweite der Zeile, ändert sich nichts. */
    val queueSecond: StateFlow<Long?> = continueListening.episodes
        .map { it.getOrNull(1)?.book?.id }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Probeabos aus der Vorschau: Ihre Folgen lassen sich nicht merken (das Abo verschwindet mit dem Verlassen der Seite). */
    val trialFeeds: StateFlow<Set<String>> = podcasts.trialFeedIds
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun playNext(bookId: Long) {
        viewModelScope.launch { continueListening.playNext(bookId) }
    }

    /** Merken (Stern) bzw. nicht mehr merken, auch für mehrere Folgen der Mehrfachauswahl. */
    fun setStarred(items: List<BookWithProgress>, starred: Boolean) {
        viewModelScope.launch { podcasts.setStarred(items.filter { it.isEpisode }.map { it.book.id }, starred) }
    }

    fun setQueued(bookId: Long, queued: Boolean) {
        viewModelScope.launch { if (queued) continueListening.append(bookId) else continueListening.remove(bookId) }
    }

    /**
     * Bücher melden den Stand auch an Audiobookshelf, Folgen haben nur den lokalen Stand. Ein Buch „ungehört“ geht aus
     * „Weiterhören“ zurück in „Zuletzt hinzugefügt“.
     */
    fun setPlayed(item: BookWithProgress, played: Boolean) {
        val id = item.book.id
        // Im Player geladen: Der hält an und schreibt den Stand selbst, sonst überschriebe er ihn beim nächsten Speichern.
        if (played && servicePlayer.markFinished(id, playNext = false)) return
        viewModelScope.launch {
            if (item.isEpisode) {
                podcasts.setPlayed(id, played)
            } else {
                val duration = item.book.durationMs
                progressSync.record(id, if (played) duration else 0, duration, finished = played, force = true, hidden = !played)
            }
        }
    }

    /** „Als abgespielt markieren und nächste spielen“ (nur Folgen): danach die nächste Folge der Warteschlange. */
    fun setPlayedAndPlayNext(item: BookWithProgress) {
        val id = item.book.id
        if (servicePlayer.markFinished(id, playNext = true)) return
        viewModelScope.launch {
            podcasts.setPlayed(id, true)
            continueListening.next(id)?.let(launcher::play)
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

    // --- Mehrfachauswahl und Wischen (Konzept 12.5): dieselben Aktionen für mehrere Folgen, in der Reihenfolge der Liste.

    /**
     * „Gehört“ bzw. „ungehört“ für alle [items]. Die im Player geladene Folge hält der Player selbst an (wie beim einzelnen
     * Menüpunkt); für die übrigen bekommt [onUndo] einen Weg zurück zum vorherigen Stand.
     */
    fun setPlayed(items: List<BookWithProgress>, played: Boolean, onUndo: (suspend () -> Unit) -> Unit = {}) {
        val ids = items.map { it.book.id }.filter { !played || !servicePlayer.markFinished(it, playNext = false) }
        if (ids.isEmpty()) return
        viewModelScope.launch { onUndo(podcasts.setPlayed(ids, played)) }
    }

    fun undo(restore: suspend () -> Unit) {
        viewModelScope.launch { restore() }
    }

    /** Ans Ende der Warteschlange, in der Reihenfolge der Auswahl; was schon darin steht, bleibt, wo es ist. */
    fun append(items: List<BookWithProgress>) {
        val inQueue = queued.value
        viewModelScope.launch { items.filter { it.book.id !in inQueue }.forEach { continueListening.append(it.book.id) } }
    }

    /** Direkt hinter die vorderste Folge, in der Reihenfolge der Auswahl (die vorderste selbst bleibt, wo sie ist). */
    fun playNext(items: List<BookWithProgress>) {
        val head = queueHead.value
        viewModelScope.launch {
            items.map { it.book.id }.filter { it != head }.asReversed().forEach { continueListening.playNext(it) }
        }
    }

    fun removeFromQueue(items: List<BookWithProgress>) {
        viewModelScope.launch { items.forEach { continueListening.remove(it.book.id) } }
    }

    fun removeDownloads(items: List<BookWithProgress>) {
        val present = downloaded.value
        viewModelScope.launch { items.filter { it.book.id in present }.forEach { downloads.remove(it.book.id) } }
    }

    /** Lädt alle noch nicht geladenen Folgen (nicht die vom Gerät); [onHint] wie bei [download], höchstens einmal. */
    fun download(items: List<BookWithProgress>, onHint: (Int) -> Unit) {
        val present = downloaded.value
        val missing = items.filter { !it.book.isLocal && it.book.id !in present }
        viewModelScope.launch {
            val starts = missing.map { downloads.download(it.book.id) }
            when {
                DownloadStart.OFFLINE in starts -> onHint(R.string.podcast_download_offline)
                DownloadStart.WAITING_FOR_WIFI in starts -> onHint(R.string.podcast_download_waiting_wifi)
            }
        }
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
