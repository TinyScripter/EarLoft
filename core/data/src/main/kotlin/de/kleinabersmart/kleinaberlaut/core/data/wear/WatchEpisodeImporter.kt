package de.kleinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.kleinaberlaut.core.data.spoken.AbsSessionReporter
import de.kleinabersmart.kleinaberlaut.core.data.spoken.BookProgressSync
import de.kleinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import de.kleinabersmart.kleinaberlaut.core.database.dao.BookDao
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookEntity
import de.kleinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import de.kleinabersmart.kleinaberlaut.core.model.BookKind
import de.kleinabersmart.kleinaberlaut.core.model.isLocalSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fortschritt einer Folge bzw. eines Buchs auf der Uhr. Schlüssel wie auf beiden Geräten: `feedId` und guid einer Folge
 * bzw. `serverId` und `remoteId` eines Buchs.
 */
data class WatchEpisodeProgress(
    val feedId: String,
    val guid: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val speed: Float,
    val updatedAt: Long,
)

/** Auf der Uhr gehörte Inhaltszeit einer Folge bzw. eines Buchs seit der letzten Meldung, gehört bis [at]. */
data class WatchEpisodeListening(val feedId: String, val guid: String, val listenedMs: Long, val at: Long)

/**
 * Übernimmt, was auf der Uhr gehört wurde (Wear-Konzept 16.1/16.2): den Fortschritt, wenn er neuer ist als der des Handys,
 * und die gehörte Zeit in die Statistik, als wäre sie hier gehört. Bei Büchern von Audiobookshelf geht beides weiter an den
 * Server: der Fortschritt wie beim Abspielen am Handy, die Zeit als Hörsitzung. Zu Ende Gehörtes fällt dadurch aus der
 * Auswahl und damit von der Uhr. Jedes Paket zählt nur einmal ([ImportedPackets]).
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

    /** @return Anzahl der übernommenen Fortschritte; ältere als der des Handys und Unbekanntes zählen nicht. */
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
                    // Folgen und Bücher vom Gerät haben keinen Server, der den Stand bekommen müsste.
                    dirty = synced,
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
        // Ohne Verbindung bleibt der Stand `dirty`; der Worker von BookProgressSync liefert ihn nach.
        toServer.forEach { progressSync.push(it) }
        applied
    }

    private suspend fun book(serverId: String, remoteId: String): BookEntity? {
        val book = bookDao.bookByRemote(serverId, remoteId)
        if (book == null) Log.d(TAG, "$serverId/$remoteId unbekannt, übersprungen")
        return book
    }

    private val BookEntity.syncsWithServer: Boolean get() = kind == BookKind.BOOK && !isLocalSource(serverId)

    private companion object {
        const val TAG = "WatchEpisodeImporter"
        const val PREFERENCES = "watch_episodes"
    }
}
