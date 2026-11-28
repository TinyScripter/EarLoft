package de.kleinabersmart.earloft.core.data.wear

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
import de.kleinabersmart.earloft.core.data.stats.StatsRepository
import de.kleinabersmart.earloft.core.database.EarLoftDatabase
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
 * out of the selection and thus off the watch. Each packet counts only once ([ImportedPackets]), and in one transaction: one
 * aborted halfway arrives again and must not count its first listening times twice.
 *
 * The listening sessions are recorded only after the transaction: [AbsSessionReporter] writes under its own lock, which the
 * phone's playback holds while it waits for the database. Taken inside the transaction, both waited for each other and
 * every write of the app hung (review3 Q1).
 */
@Singleton
class WatchEpisodeImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val database: EarLoftDatabase,
    private val bookDao: BookDao,
    private val stats: StatsRepository,
    private val progressSync: BookProgressSync,
    private val sessions: AbsSessionReporter,
) {
    private val importedPackets = ImportedPackets(context, PREFERENCES)
    private val mutex = Mutex()

    /**
     * @return number of adopted progress entries; ones older than the phone's and unknown ones don't count. `null` if
     *   [deferUnknown] and an episode or book is not known (yet): nothing is adopted, and the packet may come again (after a
     *   restore, until its subscriptions and servers are synced, review5 P1).
     */
    suspend fun import(
        packetId: String,
        progress: List<WatchEpisodeProgress>,
        listening: List<WatchEpisodeListening>,
        deferUnknown: Boolean = false,
    ): Int? = mutex.withLock {
        if (packetId in importedPackets) return 0
        if (deferUnknown) {
            val keys = progress.map { it.feedId to it.guid } + listening.map { it.feedId to it.guid }
            if (keys.distinct().any { (feedId, guid) -> bookDao.bookByRemote(feedId, guid) == null }) return null
        }
        var applied = 0
        val toServer = mutableListOf<Long>()
        val toSessions = mutableListOf<SessionTime>()
        database.withTransaction {
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
                        // Own effects set on the phone stay: the watch only knows the copy it got earlier, and listening there says
                        // nothing about a tempo that was changed here since (review4 T21).
                        speed = local?.speed ?: item.speed,
                        updatedAt = item.updatedAt,
                        // Episodes and books from the device have no server that would need to receive the state.
                        dirty = synced,
                        skipSilence = local?.skipSilence ?: item.skipSilence,
                        volumeBoostDb = local?.volumeBoostDb ?: item.volumeBoostDb,
                        // As with `updatePosition`: finishing remembers when, and a finished state stays finished since then.
                        finishedAt = when {
                            !item.finished -> null
                            local?.finished == true -> local.finishedAt ?: local.updatedAt
                            else -> item.updatedAt
                        },
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
                    toSessions += SessionTime(book.bookId, item.listenedMs, position?.positionMs, position?.durationMs, item.at)
                }
            }
        }
        // Before the sessions: if the process ends in between, a session is missing on the server, but the packet arriving
        // again doesn't count its listening time twice in the statistics.
        importedPackets.remember(packetId)
        val sessionIds = toSessions.mapNotNullTo(mutableSetOf()) {
            sessions.record(it.bookId, it.listenedMs, it.positionMs, it.durationMs, now = it.at, push = false)
        }
        // Without a connection the state stays `dirty`; the BookProgressSync worker delivers it later.
        // Listened on the watch, maybe hours ago: a newer state of another device on the server wins (review4 Y18).
        toServer.forEach { progressSync.push(it, checkServer = true) }
        sessionIds.forEach { sessions.push(it) }
        applied
    }

    private suspend fun book(serverId: String, remoteId: String): BookEntity? {
        val book = bookDao.bookByRemote(serverId, remoteId)
        if (book == null) Log.d(TAG, "$serverId/$remoteId unknown, skipped")
        return book
    }

    private val BookEntity.syncsWithServer: Boolean get() = kind == BookKind.BOOK && !isLocalSource(serverId)

    /** Listening time of a book for [AbsSessionReporter.record], collected in the transaction. */
    private class SessionTime(val bookId: Long, val listenedMs: Long, val positionMs: Long?, val durationMs: Long?, val at: Long)

    private companion object {
        const val TAG = "WatchEpisodeImporter"
        const val PREFERENCES = "watch_episodes"
    }
}
