package keinabersmart.kleinaberlaut.wear.download

import android.util.Log
import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.data.repository.CoverFiles
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaylistDao
import keinabersmart.kleinaberlaut.core.database.dao.WearDao
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.download.DownloadScheduler
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.model.DownloadState
import kotlinx.coroutines.Dispatchers
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
) {
    private val mutex = Mutex()

    /** @param replaceSchedule ersetzt einen wartenden Auftrag, z. B. wenn sich die Ladegerät-Bedingung geändert hat. */
    suspend fun plan(replaceSchedule: Boolean = false) = mutex.withLock {
        val settings = state.settings.value
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
            val reserved = wearDao.getPendingCandidates().sumOf { estimateBytes(it, settings.quality) }
            val free = withContext(Dispatchers.IO) { storage.freeBytes() }
            val budget = downloadBudget(settings, free, wearDao.getDoneBytes(), reserved)

            val plan = planWithinBudget(wanted, budget)
            downloadDao.insertIfAbsent(
                plan.toQueue.map { DownloadEntity(it.sourceUid, it.songId, DownloadState.QUEUED, pinnedBy = it.pinnedBy) },
            )
            state.setMissingForStorage(plan.missing)
            if (plan.missing > 0) Log.i(TAG, "Speicher voll: ${plan.missing} Titel nicht eingereiht (Budget $budget Bytes)")
            plan.toQueue.size
        }
        if (queued > 0) Log.d(TAG, "$queued Downloads eingereiht")
        // Auch wenn nichts mehr zu laden ist: Fehlende Cover holt derselbe Auftrag nach.
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        // Neue Downloads ersetzen einen wartenden Auftrag: Sonst hängen sie hinter dem Backoff eines früheren Fehlversuchs.
        if (downloadDao.countPending() > 0 || coversMissing) scheduler.enqueue(replace = replaceSchedule || queued > 0)
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
        const val TAG = "WearDownloadPlanner"
        const val MAX_SQL_VARIABLES = 900
    }
}
