package de.kleinabersmart.tinyamp.core.data.spoken

import android.content.Context
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
import de.kleinabersmart.tinyamp.core.data.repository.PlaybackPolicy
import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.database.entity.BookProgressEntity
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.model.BookProgress
import de.kleinabersmart.tinyamp.core.model.isLocalSource
import de.kleinabersmart.tinyamp.core.network.audiobookshelf.AbsApi
import de.kleinabersmart.tinyamp.core.network.audiobookshelf.AbsMediaProgressDto
import de.kleinabersmart.tinyamp.core.network.audiobookshelf.AbsProgressUpdate
import de.kleinabersmart.tinyamp.core.network.audiobookshelf.AbsServer
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToLong

/** Ergebnis von [BookProgressSync.pull]: der jetzt gültige Fortschritt und ob er vom Server kam und spürbar springt. */
data class PullResult(val progress: BookProgress?, val tookRemote: Boolean, val noticeable: Boolean)

/**
 * Fortschritt in beide Richtungen (Konzept 12.3): lokal sofort schreiben und als `dirty` markieren, gedrosselt an
 * Audiobookshelf melden, beim Abgleich und vor dem Abspielen die neuere Seite übernehmen.
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
     * Bücher, die von „beendet“ auf „nicht beendet“ gewechselt sind: Audiobookshelf setzt die Position dabei im selben
     * Aufruf auf 0 zurück, die Position muss deshalb noch einmal gesendet werden.
     */
    private val reopened = ConcurrentHashMap.newKeySet<Long>()

    /**
     * Hält die Position fest. Gesendet wird höchstens alle [PUSH_INTERVAL_MS], mit [force] (Pause, Titelwechsel,
     * Stop) sofort. Schlägt das Senden fehl, bleibt der Eintrag `dirty` und [BookProgressWorker] holt es nach.
     */
    suspend fun record(bookId: Long, positionMs: Long, durationMs: Long, finished: Boolean, force: Boolean = false) {
        val previous = dao.progress(bookId)
        val speed = previous?.speed ?: 1f
        if (previous?.finished == true && !finished && positionMs > 0) reopened.add(bookId)
        dao.upsertProgress(
            BookProgressEntity(
                bookId = bookId,
                positionMs = positionMs.coerceAtLeast(0),
                durationMs = durationMs,
                finished = finished,
                speed = speed,
                updatedAt = System.currentTimeMillis(),
                dirty = true,
            ),
        )
        val sinceLastPush = System.currentTimeMillis() - (lastPush[bookId] ?: 0)
        if (force || sinceLastPush >= PUSH_INTERVAL_MS) push(bookId)
    }

    /** `true`, wenn nichts mehr aussteht. */
    suspend fun push(bookId: Long): Boolean {
        val progress = dao.progress(bookId) ?: return true
        if (!progress.dirty) return true
        // Uhr: Den Stand meldet sie dem Handy (Wear-Konzept 16.2).
        if (!policy.syncsWithBookServers) return true
        val book = dao.book(bookId) ?: return true
        if (book.kind == BookKind.EPISODE || isLocalSource(book.serverId)) {
            // Folgen und Bücher vom Gerät haben keinen Server, der den Fortschritt kennen müsste.
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

    /** Sendet alles, was aussteht. `true`, wenn nichts mehr offen ist. */
    suspend fun pushPending(): Boolean = dao.dirtyProgress().map { push(it.bookId) }.all { it }

    /** Fortschritt des ganzen Kontos übernehmen, wo der Server neuer ist (beim Vollabgleich). */
    suspend fun pullAll(server: AbsServer) {
        api.me(server).mediaProgress.filter { it.episodeId == null }.forEach { applyRemote(server.serverId, it) }
    }

    /** Vor dem Abspielen: den Stand dieses einen Buchs abgleichen. Ohne Verbindung gilt der lokale Stand. */
    suspend fun pull(bookId: Long): PullResult {
        val local = dao.progress(bookId)?.toModel()
        if (!policy.syncsWithBookServers) return PullResult(local, tookRemote = false, noticeable = false)
        val book = dao.book(bookId) ?: return PullResult(local, tookRemote = false, noticeable = false)
        val server = servers.server(book.serverId) ?: return PullResult(local, tookRemote = false, noticeable = false)
        val remote = try {
            api.progress(server, book.remoteId)
        } catch (e: IOException) {
            Log.w(TAG, "Fortschritt von ${book.title} nicht abrufbar", e)
            null
        } ?: return PullResult(local, tookRemote = false, noticeable = false)
        val tookRemote = applyRemote(server.serverId, remote)
        val now = dao.progress(bookId)?.toModel()
        return PullResult(now, tookRemote, tookRemote && ProgressConflict.isNoticeable(local, remote.toModel()))
    }

    /** `true`, wenn der Stand des Servers übernommen wurde. */
    private suspend fun applyRemote(serverId: String, remote: AbsMediaProgressDto): Boolean {
        val book = dao.bookByRemote(serverId, remote.libraryItemId) ?: return false
        val local = dao.progress(book.bookId)
        val remoteModel = remote.toModel()
        if (ProgressConflict.resolve(local?.toModel(), remoteModel) == ProgressDecision.KeepLocal) return false
        dao.upsertProgress(
            BookProgressEntity(
                bookId = book.bookId,
                positionMs = remoteModel.positionMs,
                durationMs = remoteModel.durationMs.takeIf { it > 0 } ?: book.durationMs,
                finished = remoteModel.finished,
                // Die Geschwindigkeit kennt Audiobookshelf nicht: sie bleibt lokal.
                speed = local?.speed ?: 1f,
                updatedAt = remoteModel.updatedAt,
                dirty = false,
            ),
        )
        return true
    }

    private fun BookProgressEntity.toUpdate() = AbsProgressUpdate(
        currentTime = positionMs / 1000.0,
        duration = durationMs / 1000.0,
        progress = if (durationMs > 0) (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0) else 0.0,
        isFinished = finished,
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
    }
}

/** Holt nach, was offline nicht gemeldet werden konnte. Nur das Netz ist Voraussetzung. */
class BookProgressWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sync = EntryPointAccessors.fromApplication(applicationContext, ProgressEntryPoint::class.java).progressSync()
        return if (sync.pushPending()) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface ProgressEntryPoint {
        fun progressSync(): BookProgressSync
    }

    companion object {
        private const val WORK_NAME = "book-progress"

        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<BookProgressWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
