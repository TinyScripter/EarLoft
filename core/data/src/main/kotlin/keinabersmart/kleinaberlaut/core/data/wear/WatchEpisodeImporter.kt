package keinabersmart.kleinaberlaut.core.data.wear

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import keinabersmart.kleinaberlaut.core.data.stats.StatsRepository
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.entity.BookProgressEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/** Fortschritt einer Folge auf der Uhr; Abo und Folge über `feedId` und guid, die auf beiden Geräten gleich sind. */
data class WatchEpisodeProgress(
    val feedId: String,
    val guid: String,
    val positionMs: Long,
    val durationMs: Long,
    val finished: Boolean,
    val speed: Float,
    val updatedAt: Long,
)

/** Auf der Uhr gehörte Inhaltszeit einer Folge seit der letzten Meldung, gehört bis [at]. */
data class WatchEpisodeListening(val feedId: String, val guid: String, val listenedMs: Long, val at: Long)

/**
 * Übernimmt, was auf der Uhr gehört wurde (Wear-Konzept 16.1): den Fortschritt, wenn er neuer ist als der des Handys, und
 * die gehörte Zeit in die Statistik, als wäre sie hier gehört. Eine beendete Folge fällt dadurch aus „Weiterhören“ und
 * damit von der Uhr. Jedes Paket zählt nur einmal ([ImportedPackets]).
 */
@Singleton
class WatchEpisodeImporter @Inject constructor(
    @ApplicationContext context: Context,
    private val bookDao: BookDao,
    private val stats: StatsRepository,
) {
    private val importedPackets = ImportedPackets(context, PREFERENCES)
    private val mutex = Mutex()

    /** @return Anzahl der übernommenen Fortschritte; ältere als der des Handys und unbekannte Folgen zählen nicht. */
    suspend fun import(packetId: String, progress: List<WatchEpisodeProgress>, listening: List<WatchEpisodeListening>): Int = mutex.withLock {
        if (packetId in importedPackets) return 0
        var applied = 0
        for (item in progress) {
            val bookId = episodeId(item.feedId, item.guid) ?: continue
            val local = bookDao.progress(bookId)
            if (local != null && local.updatedAt >= item.updatedAt) continue
            bookDao.upsertProgress(
                BookProgressEntity(
                    bookId = bookId,
                    positionMs = item.positionMs.coerceAtLeast(0),
                    durationMs = item.durationMs,
                    finished = item.finished,
                    speed = item.speed,
                    updatedAt = item.updatedAt,
                    // Folgen haben keinen Server, der den Stand bekommen müsste.
                    dirty = false,
                ),
            )
            applied++
        }
        for (item in listening) {
            val bookId = episodeId(item.feedId, item.guid) ?: continue
            stats.recordEpisodeListening(bookId, item.listenedMs, now = item.at)
        }
        importedPackets.remember(packetId)
        applied
    }

    private suspend fun episodeId(feedId: String, guid: String): Long? {
        val book = bookDao.bookByRemote(feedId, guid)?.takeIf { it.kind == BookKind.EPISODE }
        if (book == null) Log.d(TAG, "Folge $feedId/$guid unbekannt, übersprungen")
        return book?.bookId
    }

    private companion object {
        const val TAG = "WatchEpisodeImporter"
        const val PREFERENCES = "watch_episodes"
    }
}
