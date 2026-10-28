package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.dao.PlaybackDao
import keinabersmart.kleinaberlaut.core.database.entity.PlayEventEntity
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

    /** Schreibt den Hörverlauf und gibt die Wiedergabe dem [PlayReporter] weiter (Handy: Scrobble, Uhr: ans Handy). */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long) {
        playbackDao.insertPlayEvent(PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs))
        reporter.report(songId, playedAt, listenedMs)
    }
}
