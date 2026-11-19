package de.kleinabersmart.earloft.core.data.wear

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookEntity
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.isLocalSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Progress of an episode or book on the watch. Keys as on both devices: `feedId` and guid of an episode
 * or `serverId` and `remoteId` of a book.
 */
data class WatchEpisodeProgress(
    val feedId: String,
    val guid: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    /** Own speed and own effects; `null` = those of the subscription or section. */
    val speed: Float?,
    val updatedAt: Long,
    val skipSilence: Boolean? = null,
    val volumeBoostDb: Int? = null,
)

/** Content time of an episode or book listened to on the watch since the last report, listened until [at]. */
data class WatchEpisodeListening(val feedId: String, val guid: String, val listenedMs: Long, val at: Long)

/**
 * Adopts what was listened to on the watch (Wear concept 16.1/16.2): the progress if it's newer than the phone's,
 * and the listening time into the statistics, as if listened to here. For Audiobookshelf books both are forwarded to the
 * server: the progress as when playing on the phone, the time as a listening session. Items listened to the end thereby drop
 * out of the selection and thus off the watch. Each packet counts only once ([ImportedPackets]).
 */
@Singleton
class WatchEpisodeImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val bookDao: BookDao,
    private val stats: StatsRepository,
    private val progressSync: BookProgressSync,
    private val sessions: AbsSessionReporter,
) {
    private val importedPackets = ImportedPackets(context, PREFERENCES)
    private val mutex = Mutex()

    /** @return number of adopted progress entries; ones older than the phone's and unknown ones don't count. */
    suspend fun import(packetId: String, progress: List<WatchEpisodeProgress>, listening: List<WatchEpisodeListening>): Int = mutex.withLock {
        if (packetId in importedPackets) return 0
        var applied = 0
        val toServer = mutableListOf<Long>()
        for (item in progress) {
            val book = book(item.feedId, item.guid) ?: continue
            val local = bookDao.progress(book.bookId)
            if (local != null && local.updatedAt >= item.updatedAt) continue
            val synced = book.syncsWithServer
            bookDao.upsertProgress(
                BookProgressEntity(
                    bookId = book.bookId,
                    positionMs = item.positionMs.coerceAtLeast(0),
                    durationMs = item.durationMs,
                    finished = item.finished,
                    speed = item.speed,
                    updatedAt = item.updatedAt,
                    // Episodes and books from the device have no server that would need to receive the state.
                    dirty = synced,
                    skipSilence = item.skipSilence,
                    volumeBoostDb = item.volumeBoostDb,
                ),
            )
            if (synced) toServer += book.bookId
            applied++
        }
        for (item in listening) {
            val book = book(item.feedId, item.guid) ?: continue
            if (book.kind == BookKind.EPISODE) {
                stats.recordEpisodeListening(book.bookId, item.listenedMs, now = item.at)
            } else {
                stats.recordBookListening(book.bookId, item.listenedMs, now = item.at)
                val position = bookDao.progress(book.bookId)
                sessions.record(book.bookId, item.listenedMs, position?.positionMs, position?.durationMs, force = true, now = item.at)
            }
        }
        importedPackets.remember(packetId)
        // Without a connection the state stays `dirty`; the BookProgressSync worker delivers it later.
        toServer.forEach { progressSync.push(it) }
        applied
    }

    private suspend fun book(serverId: String, remoteId: String): BookEntity? {
        val book = bookDao.bookByRemote(serverId, remoteId)
        if (book == null) Log.d(TAG, "$serverId/$remoteId unknown, skipped")
        return book
    }

    private val BookEntity.syncsWithServer: Boolean get() = kind == BookKind.BOOK && !isLocalSource(serverId)

    private companion object {
        const val TAG = "WatchEpisodeImporter"
        const val PREFERENCES = "watch_episodes"
    }
}
