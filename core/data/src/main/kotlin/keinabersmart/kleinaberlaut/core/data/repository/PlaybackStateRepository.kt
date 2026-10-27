package keinabersmart.kleinaberlaut.core.data.repository

import androidx.room.withTransaction
import keinabersmart.kleinaberlaut.core.data.sync.SyncScheduler
import keinabersmart.kleinaberlaut.core.data.sync.SyncSettings
import keinabersmart.kleinaberlaut.core.database.KleinaberlautDatabase
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.dao.SyncDao
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
import keinabersmart.kleinaberlaut.core.database.entity.SyncActionEntity
import keinabersmart.kleinaberlaut.core.model.SourceType
import keinabersmart.kleinaberlaut.core.model.SyncActionType
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Persistierte Warteschlange und Hörverlauf. */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val database: KleinaberlautDatabase,
    private val playbackDao: PlaybackDao,
    private val libraryDao: LibraryDao,
    private val syncDao: SyncDao,
    private val syncSettings: SyncSettings,
    private val syncScheduler: SyncScheduler,
) {
    suspend fun saveQueue(songIds: List<Long>) = playbackDao.replaceQueue(songIds)

    suspend fun loadQueue(): List<Long> = playbackDao.getQueueSongIds()

    /**
     * Schreibt den Hörverlauf und meldet die Wiedergabe einem Server (Scrobble), falls der Song dort
     * liegt – genau einem, dem bevorzugten, damit ein weitergeleiteter Last.fm-Account nicht doppelt zählt.
     */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long) {
        val scrobble = syncSettings.scrobble.first()
        val queued = database.withTransaction {
            playbackDao.insertPlayEvent(PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs))
            if (!scrobble) return@withTransaction false
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
