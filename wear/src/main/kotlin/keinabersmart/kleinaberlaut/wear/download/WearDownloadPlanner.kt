package keinabersmart.kleinaberlaut.wear.download

import android.util.Log
import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.data.repository.CoverFiles
import keinabersmart.kleinaberlaut.core.data.podcast.EpisodeQueue
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.download.DownloadScheduler
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.model.DownloadState
import keinabersmart.kleinaberlaut.core.model.TrackDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reiht nach jedem Manifest-Abgleich und bei geänderten Einstellungen die fehlenden Songs der Auswahlen
 * zum Download ein (Wear-Konzept 8.1). Reihenfolge: Auswahlen in der Reihenfolge des Handys, darin die
 * Song-Reihenfolge. Was nicht mehr unter die Speichergrenze passt, bleibt draußen und wird gezählt
 * ([WatchState.missingForStorage]); gelöscht wird nichts. Songs, die nur auf dem Handy liegen, werden unter der
 * Pseudo-Quelle `phone` eingereiht und über das Handy übertragen (Wear-Konzept 8.4).
 *
 * Die Podcast-Folgen vom Handy (16.1) kommen vor der Musik ins selbe Budget: Es sind wenige, und sie sind das, was gerade
 * gehört wird. Danach die Hörbücher (16.2), ebenfalls einzeln gewählt, aber groß; ein Buch kommt nur ganz oder gar nicht.
 * Reihenfolge wie in „Weiterhören“; Beendetes wird nicht mehr geladen.
 */
@Singleton
class WearDownloadPlanner @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val wearDao: WearDao,
    private val playlistDao: PlaylistDao,
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
    private val state: WatchState,
    private val scheduler: DownloadScheduler,
    private val coverFiles: CoverFiles,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val episodeQueue: EpisodeQueue,
) {
    private val mutex = Mutex()

    /** @param replaceSchedule ersetzt einen wartenden Auftrag, z. B. wenn sich die Ladegerät-Bedingung geändert hat. */
    suspend fun plan(replaceSchedule: Boolean = false) = mutex.withLock {
        val settings = state.settings.value
        val episodeOrder = episodeQueue.order.first()
        val queued = database.withTransaction {
            val ordered = orderedSongs()
            val songIds = ordered.map { it.first }
            val alreadyDownloaded = songIds.chunked(MAX_SQL_VARIABLES).flatMapTo(HashSet()) { downloadDao.getSongIdsWithDownload(it) }
            val candidates = songIds.chunked(MAX_SQL_VARIABLES).flatMap { wearDao.getServerCandidates(it) }.groupBy { it.songId }
            val phoneCandidates = songIds.chunked(MAX_SQL_VARIABLES).flatMap { wearDao.getPhoneCandidates(it) }.groupBy { it.songId }

            val wanted = ordered.mapNotNull { (songId, key) ->
                if (songId in alreadyDownloaded) return@mapNotNull null
                val best = candidates[songId]
                    ?.sortedWith(compareByDescending { it.sourcePriority })
                    ?.firstOrNull()
                    ?: phoneCandidates[songId]?.firstOrNull()
                    ?: return@mapNotNull null
                PlannedDownload(songId, best.uid, estimateBytes(best, settings.quality), pinnedBy = "wear:$key")
            }
            val episodeTracks = podcastDao.downloadedTracks() + podcastDao.pendingTracks()
            val bookTracks = bookDao.downloadedBookTracks() + bookDao.pendingBookTracks()
            val reserved = wearDao.getPendingCandidates().sumOf { estimateBytes(it, settings.quality) } +
                (episodeTracks + bookTracks).filter { it.downloadState in PENDING }.sumOf { estimateEpisodeBytes(it.durationMs) }
            val done = wearDao.getDoneBytes() +
                (episodeTracks + bookTracks).filter { it.downloadState == TrackDownload.DONE }.sumOf { it.fileSize }
            val free = withContext(Dispatchers.IO) { storage.freeBytes() }
            val budget = downloadBudget(settings, free, done, reserved)

            val episodes = episodesToLoad(episodeOrder)
            val episodesFitting = takeWithinBudget(episodes, budget) { it.second }
            episodesFitting.forEach { (bookId, _) -> podcastDao.queueDownload(bookId) }
            val afterEpisodes = budget - episodesFitting.sumOf { it.second }

            val books = booksToLoad(episodeOrder)
            val booksFitting = takeWithinBudget(books, afterEpisodes) { it.second }
            booksFitting.forEach { (bookId, _) -> bookDao.queueBookDownload(bookId) }

            val plan = planWithinBudget(wanted, afterEpisodes - booksFitting.sumOf { it.second })
            downloadDao.insertIfAbsent(
                plan.toQueue.map { DownloadEntity(it.sourceUid, it.songId, DownloadState.QUEUED, pinnedBy = it.pinnedBy) },
            )
            val missing = plan.missing + episodes.size - episodesFitting.size + books.size - booksFitting.size
            state.setMissingForStorage(missing)
            if (missing > 0) Log.i(TAG, "Speicher voll: $missing Titel, Folgen bzw. Bücher nicht eingereiht (Budget $budget Bytes)")
            plan.toQueue.size + episodesFitting.size + booksFitting.size
        }
        if (queued > 0) Log.d(TAG, "$queued Downloads eingereiht")
        // Auch wenn nichts mehr zu laden ist: Fehlende Cover holt derselbe Auftrag nach.
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        // Neue Downloads ersetzen einen wartenden Auftrag: Sonst hängen sie hinter dem Backoff eines früheren Fehlversuchs.
        val pending = downloadDao.countPending() + podcastDao.countPendingDownloads() + bookDao.countPendingBookDownloads()
        if (pending > 0 || coversMissing) scheduler.enqueue(replace = replaceSchedule || queued > 0)
    }

    /** Noch nie geladene, nicht beendete Folgen in der Reihenfolge von „Weiterhören“, mit ihrer geschätzten Größe. */
    private suspend fun episodesToLoad(order: List<Long>): List<Pair<Long, Long>> = order.mapNotNull { bookId ->
        // Die Reihenfolge enthält auch Bücher; deren erste Datei ist keine Folge.
        if (bookDao.book(bookId)?.kind != BookKind.EPISODE) return@mapNotNull null
        if (bookDao.progress(bookId)?.finished == true) return@mapNotNull null
        val track = podcastDao.trackOf(bookId)?.takeIf { it.downloadState == TrackDownload.NONE } ?: return@mapNotNull null
        bookId to estimateEpisodeBytes(track.durationMs)
    }

    /**
     * Nicht beendete Bücher mit noch nie geladenen Dateien in der Reihenfolge von „Weiterhören“, mit der geschätzten Größe
     * dieser Dateien. Ein Buch mit fehlgeschlagener Datei wartet, bis das Handy es neu ins Manifest aufnimmt (wie bei Folgen).
     */
    private suspend fun booksToLoad(order: List<Long>): List<Pair<Long, Long>> = order.mapNotNull { bookId ->
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.BOOK } ?: return@mapNotNull null
        if (bookDao.progress(book.bookId)?.finished == true) return@mapNotNull null
        val tracks = bookDao.tracks(bookId)
        val missing = tracks.filter { it.downloadState == TrackDownload.NONE }
        if (missing.isEmpty() || tracks.any { it.downloadState == TrackDownload.FAILED }) return@mapNotNull null
        bookId to missing.sumOf { estimateEpisodeBytes(it.durationMs) }
    }

    /** Alle Songs der Auswahlen in Reihenfolge, jeder einmal, mit dem Schlüssel der ersten Auswahl, die ihn enthält. */
    private suspend fun orderedSongs(): List<Pair<Long, String>> {
        val seen = HashSet<Long>()
        val result = mutableListOf<Pair<Long, String>>()
        for (selection in wearDao.getSelections()) {
            val playlistId = selection.refId ?: continue
            for (songId in playlistDao.getSongIds(playlistId)) {
                if (seen.add(songId)) result += songId to selection.key
            }
        }
        return result
    }

    private companion object {
        val PENDING = setOf(TrackDownload.QUEUED, TrackDownload.RUNNING)
        const val TAG = "WearDownloadPlanner"
        const val MAX_SQL_VARIABLES = 900
    }
}
