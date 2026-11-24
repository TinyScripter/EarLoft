package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.database.dao.PlaybackDao
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.QueueItemEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Persisted queue and listening history. */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val playbackDao: PlaybackDao,
    private val reporter: PlayReporter,
) {
    suspend fun saveQueue(songIds: List<Long>) = playbackDao.replaceQueue(songIds)

    /**
     * The stored queue by position: a song deleted since then is [QUEUE_GAP] there, so the saved index still points at
     * the saved title (review H32).
     */
    suspend fun loadQueue(): List<Long> = playbackDao.getQueueItems().toSlots()

    /** The stored queue (like [loadQueue]) on every change. */
    fun observeQueue(): Flow<List<Long>> = playbackDao.observeQueueItems().map { it.toSlots() }

    /**
     * Writes the listening history and passes the playback on to the [PlayReporter] (phone: scrobble, watch: to the phone).
     * @param live `false` for plays reported later (see [PlayReporter.report]).
     */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true) {
        val event = PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs)
        if (!playbackDao.insertPlayEventIfSongExists(event)) return
        reporter.report(songId, playedAt, listenedMs, live)
    }

    companion object {
        /** Place of a deleted song in [loadQueue]; no song has this ID. */
        const val QUEUE_GAP = -1L
    }
}

/** The song IDs at their positions, free positions as [PlaybackStateRepository.QUEUE_GAP]. */
internal fun List<QueueItemEntity>.toSlots(): List<Long> {
    if (isEmpty()) return emptyList()
    val slots = MutableList(maxOf { it.position } + 1) { PlaybackStateRepository.QUEUE_GAP }
    forEach { slots[it.position] = it.songId }
    return slots
}
