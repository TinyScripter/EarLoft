package de.kleinabersmart.tinyamp.core.data.repository

import androidx.room.withTransaction
import de.kleinabersmart.tinyamp.core.data.sync.SyncScheduler
import de.kleinabersmart.tinyamp.core.data.sync.SyncSettings
import de.kleinabersmart.tinyamp.core.database.TinyAmpDatabase
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.dao.SyncDao
import de.kleinabersmart.tinyamp.core.database.entity.SyncActionEntity
import de.kleinabersmart.tinyamp.core.model.SourceType
import de.kleinabersmart.tinyamp.core.model.SyncActionType
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Gibt eine abgeschlossene Wiedergabe weiter, nachdem sie im Hörverlauf steht. Das Handy scrobbelt über die
 * Sync-Queue ([SyncQueuePlayReporter]), die Uhr schickt sie ans Handy (Wear-Konzept 10).
 */
interface PlayReporter {
    suspend fun report(songId: Long, playedAt: Long, listenedMs: Long)
}

/**
 * Meldet die Wiedergabe einem Server (Scrobble), falls der Song dort liegt – genau einem, dem bevorzugten, damit
 * ein weitergeleiteter Last.fm-Account nicht doppelt zählt.
 */
class SyncQueuePlayReporter @Inject constructor(
    private val database: TinyAmpDatabase,
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val syncSettings: SyncSettings,
    private val syncScheduler: SyncScheduler,
) : PlayReporter {

    override suspend fun report(songId: Long, playedAt: Long, listenedMs: Long) {
        if (!syncSettings.scrobble.first()) return
        val queued = database.withTransaction {
            val server = libraryDao.getPlayableSources(songId)
                .filter { it.sourceType == SourceType.SUBSONIC }
                .minWithOrNull(SourcePreference)
                ?: return@withTransaction false
            syncDao.insert(
                SyncActionEntity(
                    sourceId = server.sourceId,
                    actionType = SyncActionType.SCROBBLE,
                    targetKey = server.uid,
                    payloadJson = (playedAt - listenedMs).toString(),
                    createdAt = playedAt,
                ),
            )
            true
        }
        if (queued) syncScheduler.enqueue()
    }
}
