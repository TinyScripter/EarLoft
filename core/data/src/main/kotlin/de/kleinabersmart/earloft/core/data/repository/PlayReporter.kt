package de.kleinabersmart.earloft.core.data.repository

import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.data.sync.SyncSettings
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.MergeDao
import de.kleinabersmart.earloft.core.database.dao.SyncDao
import de.kleinabersmart.earloft.core.database.entity.SyncActionEntity
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.datasource.RemotePlaybackReporting
import de.kleinabersmart.earloft.core.model.SyncActionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import javax.inject.Inject

/**
 * Passes on a completed playback after it is in the listening history. The phone scrobbles via the
 * sync queue ([SyncQueuePlayReporter]), the watch sends it to the phone (Wear concept 10).
 */
interface PlayReporter {
    /** @param live `false` for plays reported later (from the watch): only into the queue, no network. */
    suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true)
}

/**
 * Reports the playback to a server (scrobble) if the song is there – to exactly one, the preferred one, so that
 * a forwarding Last.fm account doesn't count it twice.
 *
 * If the server can accept plays live ([RemotePlaybackReporting], Jellyfin) and it has only just
 * ended, it goes there immediately. If that fails (offline, server gone) or it is older (e.g. from the watch),
 * it goes into the sync queue and is reported later with its timestamp on the next connection (concept 14).
 * It is enqueued beforehand: if the process ends during the live report, it stays in the queue.
 */
class SyncQueuePlayReporter @Inject constructor(
    private val libraryDao: LibraryDao,
    private val mergeDao: MergeDao,
    private val syncDao: SyncDao,
    private val syncSettings: SyncSettings,
    private val syncScheduler: SyncScheduler,
    private val sources: MusicDataSources,
) : PlayReporter {

    override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean) {
        if (!syncSettings.scrobble.first()) return
        val server = libraryDao.getPlayableSources(songId)
            .filter { it.sourceType.isServer }
            .minWithOrNull(SourcePreference)
            ?: return
        val actionId = syncDao.insert(
            SyncActionEntity(
                sourceId = server.sourceId,
                actionType = SyncActionType.SCROBBLE,
                targetKey = server.uid,
                payloadJson = (playedAt - listenedMs).toString(),
                createdAt = playedAt,
            ),
        )
        if (live && System.currentTimeMillis() - playedAt <= LIVE_WINDOW_MS && reportLive(server, songId)) {
            syncDao.deleteAction(actionId)
            return
        }
        syncScheduler.enqueue()
    }

    /**
     * @return `true` if the server accepted the playback. If the time runs out although it arrived, the server
     *   counts it twice after the later report – rarer than a lost playback.
     */
    private suspend fun reportLive(server: PlayableSourceRow, songId: Long): Boolean {
        val source = sources[server.sourceId] as? RemotePlaybackReporting ?: return false
        val durationMs = mergeDao.getSong(songId)?.durationMs?.takeIf { it > 0 } ?: return false
        return try {
            withTimeoutOrNull(LIVE_TIMEOUT_MS) { source.reportPlayback(server.remoteId, durationMs) } != null
        } catch (e: IOException) {
            Log.i(TAG, "Wiedergabe an ${server.sourceId} nicht live gemeldet, kommt in die Warteschlange: $e")
            false
        }
    }

    internal companion object {
        private const val TAG = "PlayReporter"

        /** Anything more recent is a playback that just ended; older ones (watch, reported later) need their timestamp. */
        const val LIVE_WINDOW_MS = 5 * 60_000L
        private const val LIVE_TIMEOUT_MS = 10_000L
    }
}
