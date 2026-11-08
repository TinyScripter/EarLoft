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
 * Gibt eine abgeschlossene Wiedergabe weiter, nachdem sie im Hörverlauf steht. Das Handy scrobbelt über die
 * Sync-Queue ([SyncQueuePlayReporter]), die Uhr schickt sie ans Handy (Wear-Konzept 10).
 */
interface PlayReporter {
    /** @param live `false` für nachgereichte Wiedergaben (von der Uhr): nur in die Warteschlange, kein Netz. */
    suspend fun report(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true)
}

/**
 * Meldet die Wiedergabe einem Server (Scrobble), falls der Song dort liegt – genau einem, dem bevorzugten, damit
 * ein weitergeleiteter Last.fm-Account nicht doppelt zählt.
 *
 * Kann der Server Wiedergaben live annehmen ([RemotePlaybackReporting], Jellyfin) und ist sie gerade erst zu Ende
 * gegangen, geht sie sofort dorthin. Klappt das nicht (offline, Server weg) oder ist sie älter (z. B. von der Uhr),
 * kommt sie in die Sync-Queue und wird beim nächsten Verbinden mit ihrem Zeitpunkt nachgereicht (Konzept 14).
 * Eingetragen wird sie vorher: Endet der Prozess während der Live-Meldung, bleibt sie in der Warteschlange.
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
     * @return `true`, wenn der Server die Wiedergabe angenommen hat. Läuft die Zeit ab, obwohl sie angekommen ist, zählt
     *   sie der Server nach dem Nachreichen doppelt – seltener als eine verlorene Wiedergabe.
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

        /** Jünger ist eine gerade zu Ende gegangene Wiedergabe; ältere (Uhr, nachgereicht) brauchen ihren Zeitpunkt. */
        const val LIVE_WINDOW_MS = 5 * 60_000L
        private const val LIVE_TIMEOUT_MS = 10_000L
    }
}
