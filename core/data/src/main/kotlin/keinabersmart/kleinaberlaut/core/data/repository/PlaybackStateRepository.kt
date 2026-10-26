package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Persistierte Warteschlange und Hörverlauf. */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val playbackDao: PlaybackDao,
) {
    suspend fun saveQueue(songIds: List<Long>) = playbackDao.replaceQueue(songIds)

    suspend fun loadQueue(): List<Long> = playbackDao.getQueueSongIds()

    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long) {
        playbackDao.insertPlayEvent(PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs))
    }
}
