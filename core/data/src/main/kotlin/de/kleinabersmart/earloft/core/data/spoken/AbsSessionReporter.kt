package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
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
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsException
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
 * Reports the listening time of books as sessions to Audiobookshelf (its statistics count from sessions). The app plays
 * the files directly and therefore doesn't create a server session itself, but reports each one as a "local" session with
 * totals. A session runs until nothing has been played for longer than [SESSION_GAP_MS]. Everything is stored locally first
 * and delivered later by [AbsSessionWorker] if there is no connection; resending is harmless because the server
 * replaces the totals per session ID. Books from the device and podcasts have no server and are skipped.
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
    /** Server → the token it was learned with and the account's user ID. */
    private val userIds = ConcurrentHashMap<String, Pair<String, String>>()
    private val lastPush = ConcurrentHashMap<String, Long>()

    /**
     * Books [listenedMs] of listened content time into the running session of the book [bookId], which is now at [positionMs]
     * (`null`: unknown, the previous position stays). Sent at most every [PUSH_INTERVAL_MS], with [force] (pause, switch, end) immediately.
     */
    suspend fun record(
        bookId: Long,
        listenedMs: Long,
        positionMs: Long?,
        durationMs: Long?,
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        /** `false`: the caller pushes the returned session itself, e.g. only after its transaction. */
        push: Boolean = true,
    ): String? {
        if (listenedMs <= 0) return null
        // Watch: the listening time goes to the server via the phone (Wear concept 16.2), which builds its own sessions from it.
        if (!policy.syncsWithBookServers) return null
        val sessionId = mutex.withLock {
            val book = bookDao.book(bookId) ?: return null
            if (book.kind != BookKind.BOOK || hasNoBookServer(book.serverId)) return null
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
        if (push && (force || sinceLastPush >= PUSH_INTERVAL_MS)) push(sessionId)
        return sessionId
    }

    /** `true` if the session is reported (or there is nothing to report). */
    suspend fun push(sessionId: String): Boolean = send(sessionId) == Sent.DONE

    private enum class Sent { DONE, FAILED, UNREACHABLE }

    private suspend fun send(sessionId: String): Sent {
        val session = dao.session(sessionId) ?: return Sent.DONE
        if (!session.dirty) return Sent.DONE
        val book = bookDao.book(session.bookId) ?: return Sent.DONE
        val server = servers.server(book.serverId) ?: return Sent.UNREACHABLE
        lastPush[sessionId] = System.currentTimeMillis()
        return try {
            // Per sign-in: another account on the same server (new token) has another user ID (review5 P32).
            val userId = userIds[server.serverId]?.takeIf { it.first == server.token }?.second
                ?: api.me(server).id.also { userIds[server.serverId] = server.token to it }
            // Audiobookshelf takes the session's position into the progress if the session is newer, and a position before the end
            // makes a finished book unfinished again (checked on 2.38.0). A finished book therefore reports its end, as its progress does.
            val finished = bookDao.progress(session.bookId)?.finished == true
            api.syncLocalSession(server, session.toPayload(userId, book.remoteId, book.title, book.author, atEnd = finished))
            dao.markClean(session.sessionId, session.updatedAt)
            Sent.DONE
        } catch (e: IOException) {
            Log.w(TAG, "Listening session of ${book.title} not reported", e)
            AbsSessionWorker.schedule(context)
            // The server answered (rejection, server error): the next session may still go through. Otherwise it is not reachable.
            if (e is AbsException) Sent.FAILED else Sent.UNREACHABLE
        }
    }

    /**
     * Sends all pending sessions (only those of the server [serverId], if given) and cleans up old, reported ones. `true` if nothing is
     * pending anymore. Once a server is not reachable, its remaining sessions wait for the next time: each one would otherwise wait
     * for the timeout, and the book sync calls this holding its lock (review4 8.6 Y15).
     */
    suspend fun pushPending(serverId: String? = null): Boolean {
        val unreachable = HashSet<String>()
        var done = true
        for (session in dao.dirty()) {
            val server = bookDao.book(session.bookId)?.serverId
            if (serverId != null && server != serverId) continue
            if (server != null && server in unreachable) {
                done = false
                continue
            }
            when (send(session.sessionId)) {
                Sent.DONE -> Unit
                Sent.FAILED -> done = false
                Sent.UNREACHABLE -> {
                    done = false
                    server?.let(unreachable::add)
                }
            }
        }
        dao.deleteCleanBefore(System.currentTimeMillis() - KEEP_MS)
        return done
    }

    private fun AbsSessionEntity.toPayload(userId: String, itemId: String, title: String, author: String, atEnd: Boolean): AbsLocalSession {
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
            currentTime = (if (atEnd && durationMs > 0) durationMs else currentPositionMs) / 1000.0,
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

        /** Pause after which a new session begins. */
        const val SESSION_GAP_MS = 30 * 60_000L
        const val KEEP_MS = 2 * 24 * 60 * 60_000L
    }
}

/** Catches up on what couldn't be reported while offline. Network is the only requirement. */
class AbsSessionWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val reporter = EntryPointAccessors.fromApplication(applicationContext, SessionEntryPoint::class.java).reporter()
        return CatchUpWork.result(reporter.pushPending(), runAttemptCount)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SessionEntryPoint {
        fun reporter(): AbsSessionReporter
    }

    companion object {
        private const val WORK_NAME = "abs-sessions"

        /** See [CatchUpWork.policy]. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<AbsSessionWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, CatchUpWork.policy(replace = false), request)
        }
    }
}
