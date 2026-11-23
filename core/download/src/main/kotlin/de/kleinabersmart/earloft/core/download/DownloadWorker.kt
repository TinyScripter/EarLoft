package de.kleinabersmart.earloft.core.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * Works through the download queue one by one (concept 5.3), running as a
 * foreground worker with a progress notification. Network errors put the download back and
 * let WorkManager retry with backoff; after [MAX_ATTEMPTS] it counts as failed (at most one per run,
 * [RetryBudget]).
 */
class DownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    entryPoint: DownloadEntryPoint,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) :
        this(context, params, EntryPointAccessors.fromApplication(context, DownloadEntryPoint::class.java))

    private val downloadDao = entryPoint.downloadDao()
    private val edges = entryPoint.musicEdges()
    private val storage = entryPoint.storage()
    private val space = entryPoint.space()

    override suspend fun doWork(): Result {
        downloadDao.requeueRunning()
        // The job only starts while the storage is not low: whatever waited for room may try again.
        space.enough(DownloadKind.MUSIC)
        if (downloadDao.countPending() == 0) return finish()
        startForeground(progress = null)
        val budget = RetryBudget(runAttemptCount, MAX_ATTEMPTS)

        try {
            return downloadQueue(budget)
        } catch (e: CancellationException) {
            // The partial file is already gone; the downloads page says why nothing happens until there is room (review H7).
            if (stoppedForLowStorage) space.short(DownloadKind.MUSIC)
            throw e
        }
    }

    private suspend fun downloadQueue(budget: RetryBudget): Result {
        while (true) {
            val next = downloadDao.nextQueued() ?: break
            val throttle = ProgressThrottle()
            val outcome = edges.download(next) { done, total ->
                val percent = throttle.next(done, total)
                if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
            }
            when (outcome) {
                is DownloadOutcome.Retry -> {
                    Log.w(TAG, "Download ${next.sourceUid} interrupted (attempt ${runAttemptCount + 1})", outcome.cause)
                    // Rejected credentials don't change by waiting; otherwise the song would hold up the queue.
                    if (!budget.giveUp(outcome)) {
                        downloadDao.requeue(next.sourceUid)
                        return Result.retry()
                    }
                    downloadDao.markFailed(next.sourceUid)
                    // The other songs of this server would fail the same way (thousands of requests with "download everything"); new
                    // credentials in the server form re-enqueue them.
                    if (outcome.isAuthRejection) downloadDao.failQueuedOfSource(next.sourceUid.substringBefore(':'))
                }
                is DownloadOutcome.Failed -> Log.w(TAG, "Download ${next.sourceUid} failed: ${outcome.reason}")
                is DownloadOutcome.NoSpace -> {
                    // Stays queued; the rest of the queue waits too, its songs need room just the same (review H7).
                    Log.w(TAG, "Download ${next.sourceUid}: not enough storage (${outcome.totalBytes ?: "?"} bytes), waiting for room")
                    downloadDao.requeue(next.sourceUid)
                    space.short(DownloadKind.MUSIC)
                    space.retryLater(DownloadKind.MUSIC)
                    return finish()
                }
                DownloadOutcome.Done, DownloadOutcome.Cancelled -> Unit
            }
        }
        return finish()
    }

    private suspend fun finish(): Result {
        storage.deleteOrphans(downloadDao.getAll())
        cacheCovers()
        return Result.success()
    }

    /**
     * Once per album with downloaded songs, the cover as a file (usually already there from `OfflineImages`): it doesn't count
     * towards the cover cache and stays as long as the album exists. If it's missing, playback is unaffected.
     */
    private suspend fun cacheCovers() {
        for (albumId in downloadDao.getAlbumIdsWithDoneDownload()) {
            try {
                edges.cacheAlbumCover(albumId, COVER_SIZE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover for album $albumId not loadable", e)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /** Without foreground permission (started from the background) downloading simply continues normally. */
    /**
     * From the background (e.g. retry after backoff) the system refuses the foreground service; then downloading
     * continues normally and this run doesn't ask again (otherwise an exception plus stack trace at every percent).
     */
    private var foregroundDenied = false

    private suspend fun startForeground(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(downloadDao.countPending(), progress)))
        } catch (e: CancellationException) {
            // Subclass of IllegalStateException: the worker was stopped, the foreground was not refused (review V28).
            throw e
        } catch (e: IllegalStateException) {
            foregroundDenied = true
            Log.i(TAG, "Foreground start not possible, downloading in the background (${e.javaClass.simpleName})")
        }
    }

    private suspend fun notify(progress: Int?) = startForeground(progress)

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

    private fun buildNotification(pending: Int, progress: Int?): Notification {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.download_channel_name))
                .build(),
        )
        val text = if (pending > 0) {
            applicationContext.resources.getQuantityString(R.plurals.download_notification_pending, pending, pending)
        } else {
            null
        }
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    /** Database, files and state; the servers go through [musicEdges]. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface DownloadEntryPoint {
        fun downloadDao(): DownloadDao
        fun storage(): DownloadStorage
        fun space(): DownloadSpace
        fun musicEdges(): MusicDownloadEdges
    }

    private companion object {
        const val TAG = "DownloadWorker"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2001
        const val MAX_ATTEMPTS = 5
        const val COVER_SIZE_PX = 1024
    }
}

/**
 * The ways out of the [DownloadWorker]: the music servers. What the worker decides on top of them (retries, giving up, rejected
 * credentials, running out of room) is tested against a fake, with the real database and files (review2 section 5).
 */
internal interface MusicDownloadEdges {
    /** Like [Downloader.download]: updates the state of the entry itself. */
    suspend fun download(download: DownloadEntity, onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit): DownloadOutcome

    /** Caches the album cover as a file; `false` if there is none. */
    suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean
}

internal class MusicDownloadEdgesImpl @Inject constructor(
    private val downloader: Downloader,
    private val artwork: ArtworkRepository,
) : MusicDownloadEdges {
    override suspend fun download(download: DownloadEntity, onProgress: suspend (Long, Long?) -> Unit) =
        downloader.download(download, onProgress = onProgress)

    override suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean = artwork.cacheAlbumCover(albumId, sizePx)
}

@Module
@InstallIn(SingletonComponent::class)
internal interface MusicDownloadEdgesModule {
    @Binds
    fun bindMusicDownloadEdges(edges: MusicDownloadEdgesImpl): MusicDownloadEdges
}
