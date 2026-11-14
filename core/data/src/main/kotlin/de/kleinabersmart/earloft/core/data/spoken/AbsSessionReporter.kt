package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.PlaybackPolicy
import de.kleinabersmart.earloft.core.database.dao.AbsSessionDao
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.AbsSessionEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.hasNoBookServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsDeviceInfo
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsLocalSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet die Hörzeit von Büchern als Sitzungen an Audiobookshelf (dessen Statistik zählt aus Sitzungen). Die App spielt
 * die Dateien direkt ab und legt deshalb selbst keine Server-Sitzung an, sondern meldet jede als „lokale“ Sitzung mit
 * Summen. Eine Sitzung läuft, bis länger als [SESSION_GAP_MS] nichts gehört wurde. Alles wird zuerst lokal gespeichert
 * und bei fehlender Verbindung von [AbsSessionWorker] nachgeliefert; wiederholtes Senden ist unschädlich, weil der Server
 * die Summen je Sitzungs-ID ersetzt. Bücher vom Gerät und Podcasts haben keinen Server und werden übergangen.
 */
@Singleton
class AbsSessionReporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: AbsSessionDao,
    private val bookDao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val policy: PlaybackPolicy,
) {
    private val mutex = Mutex()
    private val activeSession = ConcurrentHashMap<Long, String>()
    private val userIds = ConcurrentHashMap<String, String>()
    private val lastPush = ConcurrentHashMap<String, Long>()

    /**
     * Bucht [listenedMs] gehörte Inhaltszeit in die laufende Sitzung des Buchs [bookId] ein, die jetzt bei [positionMs]
     * steht (`null`: unbekannt, die bisherige Position bleibt). Gesendet wird höchstens alle [PUSH_INTERVAL_MS], mit [force] (Pause, Wechsel, Ende) sofort.
     */
    suspend fun record(
        bookId: Long,
        listenedMs: Long,
        positionMs: Long?,
        durationMs: Long?,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ) {
        if (listenedMs <= 0) return
        // Uhr: Die Hörzeit geht über das Handy an den Server (Wear-Konzept 16.2), das daraus eigene Sitzungen bildet.
        if (!policy.syncsWithBookServers) return
        val sessionId = mutex.withLock {
            val book = bookDao.book(bookId) ?: return
            if (book.kind != BookKind.BOOK || hasNoBookServer(book.serverId)) return
            val existing = activeSession[bookId]?.let { dao.session(it) }?.takeIf { now - it.updatedAt <= SESSION_GAP_MS }
            val session = existing?.copy(
                updatedAt = now,
                timeListeningMs = existing.timeListeningMs + listenedMs,
                currentPositionMs = positionMs ?: existing.currentPositionMs,
                durationMs = durationMs?.takeIf { it > 0 } ?: existing.durationMs,
                dirty = true,
            ) ?: AbsSessionEntity(
                sessionId = UUID.randomUUID().toString(),
                bookId = bookId,
                startedAt = now - listenedMs,
                updatedAt = now,
                timeListeningMs = listenedMs,
                startPositionMs = ((positionMs ?: 0) - listenedMs).coerceAtLeast(0),
                currentPositionMs = positionMs ?: 0,
                durationMs = durationMs?.takeIf { it > 0 } ?: book.durationMs,
            )
            dao.upsert(session)
            activeSession[bookId] = session.sessionId
            session.sessionId
        }
        val sinceLastPush = now - (lastPush[sessionId] ?: 0)
        if (force || sinceLastPush >= PUSH_INTERVAL_MS) push(sessionId)
    }

    /** `true`, wenn die Sitzung gemeldet ist (oder es nichts zu melden gibt). */
    suspend fun push(sessionId: String): Boolean {
        val session = dao.session(sessionId) ?: return true
        if (!session.dirty) return true
        val book = bookDao.book(session.bookId) ?: return true
        val server = servers.server(book.serverId) ?: return false
        lastPush[sessionId] = System.currentTimeMillis()
        return try {
            val userId = userIds[server.serverId] ?: api.me(server).id.also { userIds[server.serverId] = it }
            api.syncLocalSession(server, session.toPayload(userId, book.remoteId, book.title, book.author))
            dao.markClean(session.sessionId, session.updatedAt)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Hörsitzung von ${book.title} nicht gemeldet", e)
            AbsSessionWorker.schedule(context)
            false
        }
    }

    /** Sendet alle ausstehenden Sitzungen und räumt alte, gemeldete auf. `true`, wenn nichts mehr offen ist. */
    suspend fun pushPending(): Boolean {
        val done = dao.dirty().map { push(it.sessionId) }.all { it }
        dao.deleteCleanBefore(System.currentTimeMillis() - KEEP_MS)
        return done
    }

    private fun AbsSessionEntity.toPayload(userId: String, itemId: String, title: String, author: String): AbsLocalSession {
        val day = Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault())
        return AbsLocalSession(
            id = sessionId,
            userId = userId,
            libraryItemId = itemId,
            mediaType = "book",
            displayTitle = title,
            displayAuthor = author,
            duration = durationMs / 1000.0,
            playMethod = PLAY_METHOD_LOCAL,
            mediaPlayer = CLIENT_NAME,
            deviceInfo = AbsDeviceInfo(
                deviceId = "$CLIENT_NAME-${Build.MANUFACTURER}-${Build.MODEL}".lowercase(Locale.ROOT).replace(' ', '-'),
                clientName = CLIENT_NAME,
                clientVersion = CLIENT_VERSION,
                manufacturer = Build.MANUFACTURER,
                model = Build.MODEL,
            ),
            date = day.toLocalDate().toString(),
            dayOfWeek = day.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH),
            timeListening = timeListeningMs / 1000.0,
            startTime = startPositionMs / 1000.0,
            currentTime = currentPositionMs / 1000.0,
            startedAt = startedAt,
            updatedAt = updatedAt,
        )
    }

    private companion object {
        const val TAG = "AbsSessionReporter"
        const val CLIENT_NAME = "EarLoft"
        const val CLIENT_VERSION = "1.0"
        const val PLAY_METHOD_LOCAL = 3
        const val PUSH_INTERVAL_MS = 30_000L

        /** Pause, nach der eine neue Sitzung beginnt. */
        const val SESSION_GAP_MS = 30 * 60_000L
        const val KEEP_MS = 2 * 24 * 60 * 60_000L
    }
}

/** Holt nach, was offline nicht gemeldet werden konnte. Nur das Netz ist Voraussetzung. */
class AbsSessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reporter = EntryPointAccessors.fromApplication(applicationContext, SessionEntryPoint::class.java).reporter()
        return if (reporter.pushPending()) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SessionEntryPoint {
        fun reporter(): AbsSessionReporter
    }

    companion object {
        private const val WORK_NAME = "abs-sessions"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AbsSessionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
