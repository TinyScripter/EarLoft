package de.kleinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.kleinaberlaut.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.kleinaberlaut.core.database.dao.WearDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Eine Wiedergabe auf der Uhr; [songUid] ist irgendeine Quelle des Songs, die auch das Handy kennt. */
data class WatchPlay(val songUid: String, val playedAt: Long, val listenedMs: Long)

/**
 * Übernimmt die Wiedergaben der Uhr in den Hörverlauf des Handys und scrobbelt sie wie eigene (Wear-Konzept 10).
 * Ein Paket wird nur einmal importiert: Stürzt das Handy zwischen Import und Löschen des DataItems ab, kommt
 * es erneut an ([ImportedPackets]).
 */
@Singleton
class WatchPlayImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val wearDao: WearDao,
    private val playbackState: PlaybackStateRepository,
) {
    private val importedPackets = ImportedPackets(context, PREFERENCES)
    private val mutex = Mutex()

    /**
     * @return Anzahl der übernommenen Wiedergaben; Songs, die das Handy nicht kennt (z. B. nach einem
     *   Abgleich entfernt), und schon importierte Pakete zählen nicht.
     */
    suspend fun import(packetId: String, plays: List<WatchPlay>): Int = mutex.withLock {
        if (packetId in importedPackets) return 0
        val songIds = plays.map { it.songUid }.distinct().chunked(MAX_SQL_VARIABLES)
            .flatMap { wearDao.getSongIdsOfSources(it) }
            .associate { it.uid to it.songId }
        var imported = 0
        for (play in plays) {
            val songId = songIds[play.songUid]
            if (songId == null) {
                Log.d(TAG, "Wiedergabe von ${play.songUid} übersprungen: Song unbekannt")
                continue
            }
            playbackState.recordPlay(songId, play.playedAt, play.listenedMs)
            imported++
        }
        importedPackets.remember(packetId)
        imported
    }

    private companion object {
        const val TAG = "WatchPlayImporter"
        const val PREFERENCES = "watch_plays"
        const val MAX_SQL_VARIABLES = 900
    }
}
