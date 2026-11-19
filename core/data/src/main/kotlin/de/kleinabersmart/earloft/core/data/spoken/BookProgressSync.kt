package de.kleinabersmart.earloft.core.data.spoken

import android.content.Context
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
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookProgressEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.model.BookProgress
import de.kleinabersmart.earloft.core.model.hasNoBookServer
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsMeResponse
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsMediaProgressDto
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsProgressUpdate
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsServer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Result of [BookProgressSync.pull]: the progress now in effect and whether it came from the server and jumps noticeably. */
data class PullResult(val progress: BookProgress?, val tookRemote: Boolean, val noticeable: Boolean)

/**
 * Progress in both directions (concept 12.3): write locally immediately and mark as `dirty`, report to Audiobookshelf
 * throttled, adopt the newer side during sync and before playback.
 */
@Singleton
class BookProgressSync @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val policy: PlaybackPolicy,
) {
    private val lastPush = ConcurrentHashMap<Long, Long>()

    /**
     * Books that switched from "finished" to "not finished": Audiobookshelf resets the position to 0 in the same
     * call, so the position has to be sent once more.
     */
    private val reopened = ConcurrentHashMap.newKeySet<Long>()

    private val _held = MutableSharedFlow<Long>(extraBufferCapacity = HELD_BUFFER)

    /**
     * Books or episodes whose state was just recorded with `force` (pause, track change, stop), after it has been
     * stored. The watch then reports it to the phone immediately instead of only after the quiet period (Wear concept 16.1).
     */
    val held: SharedFlow<Long> = _held.asSharedFlow()

    /**
     * Records the position. It is sent at most every [PUSH_INTERVAL_MS], immediately with [force] (pause, track change,
     * stop). If sending fails, the entry stays `dirty` and [BookProgressWorker] catches up.
     * [hidden]: remove from "Continue listening" (reset, "unplayed"); any other state brings the book back in.
     */
    suspend fun record(bookId: Long, positionMs: Long, durationMs: Long, finished: Boolean, force: Boolean = false, hidden: Boolean = false) {
        // Subscription ended or book deleted while still loaded in the player: no more progress (foreign key).
        if (dao.book(bookId) == null) return
        val previous = dao.progress(bookId)
        if (previous?.finished == true && !finished && positionMs > 0) reopened.add(bookId)
        // Only write the position columns: otherwise a speed change between reading and writing would be lost.
        val updated = dao.updatePosition(
            bookId, positionMs.coerceAtLeast(0), durationMs, finished, System.currentTimeMillis(), dirty = true, hidden = hidden,
        )
        if (updated == 0) {
            dao.upsertProgress(
                BookProgressEntity(
                    bookId = bookId,
                    positionMs = positionMs.coerceAtLeast(0),
                    durationMs = durationMs,
                    finished = finished,
                    updatedAt = System.currentTimeMillis(),
                    dirty = true,
                    hidden = hidden,
                    finishedAt = System.currentTimeMillis().takeIf { finished },
                ),
            )
        }
        if (force) _held.tryEmit(bookId)
        val sinceLastPush = System.currentTimeMillis() - (lastPush[bookId] ?: 0)
        if (force || sinceLastPush >= PUSH_INTERVAL_MS) push(bookId)
    }

    /**
     * "Remove from Continue listening": the state stays, but the book appears in no row until it plays again; with
     * Audiobookshelf on all devices (`hideFromContinueListening`).
     */
    suspend fun hideFromContinueListening(bookId: Long) {
        val progress = dao.progress(bookId) ?: return
        if (progress.hidden) return
        dao.upsertProgress(progress.hiddenAt(System.currentTimeMillis()))
        push(bookId)
    }

    /**
     * Like [hideFromContinueListening] for several books ("Clear queue"): first all out of here, reporting happens
     * in the background ([BookProgressWorker]) so nothing is lost if the screen is closed before.
     */
    suspend fun hideFromContinueListening(bookIds: Collection<Long>) {
        val now = System.currentTimeMillis()
        val shown = bookIds.mapNotNull { dao.progress(it) }.filter { !it.hidden }
        if (shown.isEmpty()) return
        shown.forEach { dao.upsertProgress(it.hiddenAt(now)) }
        BookProgressWorker.schedule(context, replace = true)
    }

    /** `true` if nothing is pending anymore. */
    suspend fun push(bookId: Long): Boolean {
        val progress = dao.progress(bookId) ?: return true
        if (!progress.dirty) return true
        // Watch: it reports the state to the phone (Wear concept 16.2).
        if (!policy.syncsWithBookServers) return true
        val book = dao.book(bookId) ?: return true
        if (book.kind == BookKind.EPISODE || hasNoBookServer(book.serverId)) {
            // Episodes and books from the device or from ARD Sounds have no server that would need to know the progress.
            dao.markClean(bookId, progress.updatedAt)
            return true
        }
        val server = servers.server(book.serverId) ?: return false
        lastPush[bookId] = System.currentTimeMillis()
        return try {
            api.updateProgress(server, book.remoteId, progress.toUpdate())
            if (reopened.remove(bookId) && !progress.finished && progress.positionMs > 0) {
                api.updateProgress(server, book.remoteId, progress.toUpdate())
            }
            dao.markClean(bookId, progress.updatedAt)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Fortschritt von ${book.title} nicht gesendet", e)
            BookProgressWorker.schedule(context)
            false
        }
    }

    /** Sends everything pending. `true` if nothing is open anymore. */
    suspend fun pushPending(): Boolean = dao.dirtyProgress().map { push(it.bookId) }.all { it }

    /**
     * Adopt the whole account's progress where the server is newer (during a full sync). Returns the response so that
     * the bookmarks from the same fetch can be adopted ([Bookmarks.pull]).
     */
    suspend fun pullAll(server: AbsServer): AbsMeResponse {
        val me = api.me(server)
        me.mediaProgress.filter { it.episodeId == null }.forEach { applyRemote(server.serverId, it) }
        return me
    }

    /** Only the state on this device, without asking the server. */
    suspend fun local(bookId: Long): PullResult = PullResult(dao.progress(bookId)?.toModel(), tookRemote = false, noticeable = false)

    /** Before playback: reconcile the state of this one book. Without a connection the local state applies. */
    suspend fun pull(bookId: Long): PullResult {
        val local = dao.progress(bookId)?.toModel()
        if (!policy.syncsWithBookServers) return PullResult(local, tookRemote = false, noticeable = false)
        val book = dao.book(bookId) ?: return PullResult(local, tookRemote = false, noticeable = false)
        val server = servers.server(book.serverId) ?: return PullResult(local, tookRemote = false, noticeable = false)
        // Short: the user is waiting for playback to start (headphones, continue listening). If the server is only reachable at
        // home, playing and resuming would otherwise hang until the connection timeout (15 s); then the own state applies.
        val remote = try {
            withTimeoutOrNull(PULL_TIMEOUT_MS) { api.progress(server, book.remoteId) }
        } catch (e: IOException) {
            Log.w(TAG, "Fortschritt von ${book.title} nicht abrufbar", e)
            null
        } ?: return PullResult(local, tookRemote = false, noticeable = false)
        val tookRemote = applyRemote(server.serverId, remote)
        val now = dao.progress(bookId)?.toModel()
        return PullResult(now, tookRemote, tookRemote && ProgressConflict.isNoticeable(local, remote.toModel()))
    }

    /** `true` if the server's state was adopted. */
    private suspend fun applyRemote(serverId: String, remote: AbsMediaProgressDto): Boolean {
        val book = dao.bookByRemote(serverId, remote.libraryItemId) ?: return false
        val local = dao.progress(book.bookId)
        val remoteModel = remote.toModel()
        if (ProgressConflict.resolve(local?.toModel(), remoteModel) == ProgressDecision.KeepLocal) return false
        val durationMs = remoteModel.durationMs.takeIf { it > 0 } ?: book.durationMs
        // Audiobookshelf doesn't know speed and effects: they stay local (as in [record], only write the position columns).
        val updated = dao.updatePosition(
            book.bookId, remoteModel.positionMs, durationMs, remoteModel.finished, remoteModel.updatedAt,
            dirty = false, hidden = remote.hideFromContinueListening,
        )
        if (updated == 0) {
            dao.upsertProgress(
                BookProgressEntity(
                    bookId = book.bookId,
                    positionMs = remoteModel.positionMs,
                    durationMs = durationMs,
                    finished = remoteModel.finished,
                    updatedAt = remoteModel.updatedAt,
                    dirty = false,
                    hidden = remote.hideFromContinueListening,
                    finishedAt = remoteModel.updatedAt.takeIf { remoteModel.finished },
                ),
            )
        }
        return true
    }

    /**
     * Removed from "Continue listening". A finished book without `finishedAt` (from a backup, or a row that didn't
     * set it) keeps the previous `updatedAt` as the time it finished: otherwise deleting its downloads would be postponed.
     */
    private fun BookProgressEntity.hiddenAt(now: Long) = copy(
        hidden = true,
        updatedAt = now,
        dirty = true,
        finishedAt = finishedAt ?: updatedAt.takeIf { finished },
    )

    private fun BookProgressEntity.toUpdate() = AbsProgressUpdate(
        currentTime = positionMs / 1000.0,
        duration = durationMs / 1000.0,
        progress = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0,
        isFinished = finished,
        hideFromContinueListening = hidden,
    )

    private fun AbsMediaProgressDto.toModel() = BookProgress(
        positionMs = (currentTime * 1000).roundToLong(),
        durationMs = (duration * 1000).roundToLong(),
        finished = isFinished,
        updatedAt = lastUpdate,
    )

    private companion object {
        const val TAG = "BookProgressSync"
        const val PUSH_INTERVAL_MS = 30_000L
        const val PULL_TIMEOUT_MS = 3_000L

        /** Without a listener (on the phone) reports get lost; with a listener a few suffice, it queries all states anyway. */
        const val HELD_BUFFER = 8
    }
}

/** Catches up on what couldn't be reported offline (progress and bookmarks). Only requires network. */
class BookProgressWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, ProgressEntryPoint::class.java)
        val progressDone = entryPoint.progressSync().pushPending()
        val bookmarksDone = entryPoint.bookmarks().pushPending()
        return CatchUpWork.result(progressDone && bookmarksDone, runAttemptCount)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ProgressEntryPoint {
        fun progressSync(): BookProgressSync
        fun bookmarks(): Bookmarks
    }

    companion object {
        private const val WORK_NAME = "book-progress"

        /** See [CatchUpWork.policy]. */
        fun schedule(context: Context, replace: Boolean = false) {
            val request = OneTimeWorkRequestBuilder<BookProgressWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, CatchUpWork.policy(replace), request)
        }
    }
}
