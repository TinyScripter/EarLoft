package de.kleinabersmart.earloft.core.data.wear

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.PlaybackStateRepository
import de.kleinabersmart.earloft.core.data.sync.SyncScheduler
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
import de.kleinabersmart.earloft.core.database.dao.WearDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** A play on the watch; [songUid] is any source of the song that the phone also knows. */
data class WatchPlay(val songUid: String, val playedAt: Long, val listenedMs: Long)

/**
 * Adopts the watch's plays into the phone's listening history and scrobbles them like its own (Wear concept 10).
 * A packet is imported only once: if the phone crashes between import and deleting the DataItem,
 * it arrives again ([ImportedPackets]). All plays of a packet are written in one transaction: a packet aborted halfway arrives
 * again and must not count its first plays twice. The sync queue that scrobbles them starts only after it (review3 Q11).
 */
@Singleton
class WatchPlayImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val database: EarLoftDatabase,
    private val wearDao: WearDao,
    private val playbackState: PlaybackStateRepository,
    private val syncScheduler: SyncScheduler,
) {
    private val importedPackets = ImportedPackets(context, PREFERENCES)
    private val mutex = Mutex()

    /**
     * @return number of adopted plays; songs the phone doesn't know (e.g. removed by a
     *   sync) and already imported packets don't count.
     */
    suspend fun import(packetId: String, plays: List<WatchPlay>): Int = mutex.withLock {
        if (packetId in importedPackets) return 0
        val songIds = plays.map { it.songUid }.distinct().chunked(MAX_SQL_VARIABLES)
            .flatMap { wearDao.getSongIdsOfSources(it) }
            .associate { it.uid to it.songId }
        var imported = 0
        database.withTransaction {
            for (play in plays) {
                val songId = songIds[play.songUid]
                if (songId == null) {
                    Log.d(TAG, "Play of ${play.songUid} skipped: song unknown")
                    continue
                }
                // Not live: otherwise every report would wait up to 10 s for the network, and the packet would only count as adopted afterwards.
                playbackState.recordPlay(songId, play.playedAt, play.listenedMs, live = false)
                imported++
            }
        }
        importedPackets.remember(packetId)
        if (imported > 0) syncScheduler.enqueue()
        imported
    }

    private companion object {
        const val TAG = "WatchPlayImporter"
        const val PREFERENCES = "watch_plays"
        const val MAX_SQL_VARIABLES = 900
    }
}
