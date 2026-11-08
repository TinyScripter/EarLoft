package de.kleinabersmart.tinyamp.core.data.repository

import de.kleinabersmart.tinyamp.core.database.dao.PlaybackDao
import de.kleinabersmart.tinyamp.core.database.entity.PlayEventEntity
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

    /**
     * Schreibt den Hörverlauf und gibt die Wiedergabe dem [PlayReporter] weiter (Handy: Scrobble, Uhr: ans Handy).
     * @param live `false` für nachgereichte Wiedergaben (siehe [PlayReporter.report]).
     */
    suspend fun recordPlay(songId: Long, playedAt: Long, listenedMs: Long, live: Boolean = true) {
        playbackDao.insertPlayEvent(PlayEventEntity(songId = songId, playedAt = playedAt, listenedMs = listenedMs))
        reporter.report(songId, playedAt, listenedMs, live)
    }
}
