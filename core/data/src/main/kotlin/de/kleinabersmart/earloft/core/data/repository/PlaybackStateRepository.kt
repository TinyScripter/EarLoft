package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.database.dao.PlaybackDao
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted queue and listening history. */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val playbackDao: PlaybackDao,
    private val reporter: PlayReporter,
) {
    suspend fun saveQueue(songIds: List<Long>) = playbackDao.replaceQueue(songIds)

    suspend fun loadQueue(): List<Long> = playbackDao.getQueueSongIds()

    /** The stored queue on every change. */
    fun observeQueue(): Flow<List<Long>> = playbackDao.observeQueueSongIds()

    /**
     * Writes the listening history and passes the playback on to the [PlayReporter] (phone: scrobble, watch: to the phone).
     * @param live `false` for plays reported later (see [PlayReporter.report]).
     */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true) {
        val event = PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs)
        if (!playbackDao.insertPlayEventIfSongExists(event)) return
        reporter.report(songId, playedAt, listenedMs, live)
    }
}
