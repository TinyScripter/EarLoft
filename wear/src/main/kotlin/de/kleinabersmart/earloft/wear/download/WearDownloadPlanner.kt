package de.kleinabersmart.earloft.wear.download

import android.util.Log
import androidx.room.withTransaction
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.data.podcast.EpisodeQueue
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.PlaylistDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.DownloadState
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After every manifest sync and on changed settings, queues the missing songs of the selections
 * for download (Wear concept 8.1). Order: selections in the phone's order, within them the
 * song order. Whatever no longer fits under the storage limit stays out and is counted
 * ([WatchState.missingForStorage]); nothing is deleted. Songs that exist only on the phone are queued under the
 * pseudo source `phone` and transferred via the phone (Wear concept 8.4).
 *
 * The podcast episodes from the phone (16.1) go into the same budget before the music: there are few of them, and they are
 * what is being listened to right now. Then the audiobooks (16.2), also chosen individually, but large; a book comes entirely or not at all.
 * Order as in "Continue listening"; finished items are no longer downloaded.
 */
@Singleton
class WearDownloadPlanner @Inject constructor(
    private val database: EarLoftDatabase,
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

    /**
     * @param replaceSchedule replaces a waiting job, e.g. when the phone is back.
     * @param restart also replaces a running one: the settings it depends on (quality, charger) have changed.
     */
    suspend fun plan(replaceSchedule: Boolean = false, restart: Boolean = false) = mutex.withLock {
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
                PlannedDownload(songId, best.uid, estimateBytes(best, settings.quality, state.songSize(best.uid)), pinnedBy = "wear:$key")
            }
            val episodeTracks = podcastDao.downloadedTracks() + podcastDao.pendingTracks()
            val bookTracks = bookDao.downloadedBookTracks() + bookDao.pendingBookTracks()
            val reserved = wearDao.getPendingCandidates().sumOf { estimateBytes(it, settings.quality, state.songSize(it.uid)) } +
                (episodeTracks + bookTracks).filter { it.downloadState in PENDING }.sumOf { estimate(it) }
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
            if (missing > 0) Log.i(TAG, "Storage full: $missing songs, episodes or books not queued (budget $budget bytes)")
            plan.toQueue.size + episodesFitting.size + booksFitting.size
        }
        if (queued > 0) Log.d(TAG, "$queued downloads queued")
        // Even if there is nothing left to download: the same job fetches missing covers.
        // Albums whose cover was just missing don't start the job again (review G48).
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload()
            .any { !coverFiles.has(it) && !state.coverRecentlyMissing(WatchState.albumCoverKey(it)) }
        // New downloads replace a waiting job: otherwise they would be stuck behind the backoff of an earlier failed attempt.
        // Without new ones a waiting or running job is enough.
        val pending = downloadDao.countPending() + podcastDao.countPendingDownloads() + bookDao.countPendingBookDownloads()
        if (pending > 0 || coversMissing) {
            when {
                restart -> scheduler.restart()
                replaceSchedule || queued > 0 -> scheduler.enqueue(replace = true)
                else -> scheduler.resume()
            }
        }
    }

    /** Never downloaded, unfinished episodes in the order of "Continue listening", with their estimated size. */
    private suspend fun episodesToLoad(order: List<Long>): List<Pair<Long, Long>> = order.mapNotNull { bookId ->
        // The order also contains books; their first file is not an episode.
        if (bookDao.book(bookId)?.kind != BookKind.EPISODE) return@mapNotNull null
        if (bookDao.progress(bookId)?.finished == true) return@mapNotNull null
        val track = podcastDao.trackOf(bookId)?.takeIf { it.downloadState == TrackDownload.NONE } ?: return@mapNotNull null
        bookId to estimate(track)
    }

    /**
     * Unfinished books with never-downloaded files in the order of "Continue listening", with the estimated size of
     * these files. A book with a failed file waits until the phone adds it to the manifest again (as with episodes).
     * Files that are no longer available do not count: they will never be downloaded.
     */
    private suspend fun booksToLoad(order: List<Long>): List<Pair<Long, Long>> = order.mapNotNull { bookId ->
        val book = bookDao.book(bookId)?.takeIf { it.kind == BookKind.BOOK } ?: return@mapNotNull null
        if (bookDao.progress(book.bookId)?.finished == true) return@mapNotNull null
        val tracks = bookDao.tracks(bookId).filter { it.unavailableSince == null }
        val missing = tracks.filter { it.downloadState == TrackDownload.NONE }
        if (missing.isEmpty() || tracks.any { it.downloadState == TrackDownload.FAILED }) return@mapNotNull null
        bookId to missing.sumOf { estimate(it) }
    }

    /** The size of an episode or book file: the real one if it once did not fit, otherwise estimated from the duration. */
    private fun estimate(track: BookTrackEntity): Long =
        state.fileSize(track.bookId, track.trackIndex) ?: estimateEpisodeBytes(track.durationMs)

    /** All songs of the selections in order, each once, with the key of the first selection that contains it. */
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
