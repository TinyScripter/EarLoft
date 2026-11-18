package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.database.dao.PlaybackDao
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Persistierte Warteschlange und Hörverlauf. */
@Singleton
class PlaybackStateRepository @Inject constructor(
    private val playbackDao: PlaybackDao,
    private val reporter: PlayReporter,
) {
    suspend fun saveQueue(songIds: List<Long>) = playbackDao.replaceQueue(songIds)

    suspend fun loadQueue(): List<Long> = playbackDao.getQueueSongIds()

    /** Die gespeicherte Warteschlange bei jeder Änderung. */
    fun observeQueue(): Flow<List<Long>> = playbackDao.observeQueueSongIds()

    /**
     * Schreibt den Hörverlauf und gibt die Wiedergabe dem [PlayReporter] weiter (Handy: Scrobble, Uhr: ans Handy).
     * @param live `false` für nachgereichte Wiedergaben (siehe [PlayReporter.report]).
     */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true) {
        val event = PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs)
        if (!playbackDao.insertPlayEventIfSongExists(event)) return
        reporter.report(songId, playedAt, listenedMs, live)
    }
}
