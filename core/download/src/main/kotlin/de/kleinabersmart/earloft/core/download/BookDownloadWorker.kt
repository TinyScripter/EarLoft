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
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.model.TrackDownload
import kotlinx.coroutines.CancellationException

/**
 * Works through the queue of book downloads file by file. Structured like the [EpisodeDownloadWorker]:
 * foreground worker with progress, network errors put the entry back and retry with backoff, then from the
 * point where the partial file ends. With [KEY_MANUAL_ONLY] (a job that also downloads over mobile data) it only takes the
 * manually enqueued files and then hands the automation's files to a Wi-Fi job ([BookDownloads.continueOnWifi]);
 * WorkManager stops that one as soon as Wi-Fi is gone.
 */
class BookDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, BookEntryPoint::class.java)
    private val dao = entryPoint.bookDao()
    private val downloader = entryPoint.bookDownloader()
    private val storage = entryPoint.bookStorage()
    private val progress = entryPoint.bookProgress()
    private val manualOnly = inputData.getBoolean(KEY_MANUAL_ONLY, false)

    override suspend fun doWork(): Result {
        dao.requeueRunningBookDownloads()
        if (dao.countPendingBookDownloads() == 0) return finish()
        // Partial files from an earlier, aborted run are kept: the downloader resumes there.
        startForeground(progress = null)
        val budget = RetryBudget(runAttemptCount, MAX_ATTEMPTS)

        while (true) {
            val next = (if (manualOnly) dao.nextQueuedManualBookTrack() else dao.nextQueuedBookTrack()) ?: break
            val files = dao.tracks(next.bookId)
            val finished = files.filter { it.downloadState == TrackDownload.DONE }
            val filesDone = finished.size
            val finishedBytes = finished.sumOf { it.fileSize }
            val throttle = ProgressThrottle()
            progress.report(next.bookId, filesDone, files.size, finishedBytes, 0, null)
            val outcome = try {
                downloader.download(next) { done, total ->
                    progress.report(next.bookId, filesDone, files.size, finishedBytes, done, total)
                    val percent = throttle.next(done, total)
                    if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
                }
            } finally {
                progress.clear(next.bookId)
            }
            when (outcome) {
                is DownloadOutcome.Retry -> {
                    Log.w(TAG, "Book ${next.bookId} file ${next.trackIndex} interrupted (attempt ${runAttemptCount + 1})", outcome.cause)
                    if (!budget.giveUp(outcome)) {
                        dao.requeueBookTrack(next.bookId, next.trackIndex)
                        return Result.retry()
                    }
                    if (outcome.isAuthRejection) {
                        dao.markBookDownloadFailed(next.bookId)
                    } else {
                        // Only this file gives up; "Retry" later downloads exactly the missing ones.
                        dao.markBookTrackFailed(next.bookId, next.trackIndex)
                        storage.deletePart(next.bookId, next.trackIndex)
                    }
                }
                is DownloadOutcome.Failed -> Log.w(TAG, "Book ${next.bookId} failed: ${outcome.reason}")
                DownloadOutcome.Done, DownloadOutcome.Cancelled -> Unit
            }
        }
        return finish()
    }

    /** Removes files that no longer have an entry (book deleted on the server or rescanned). */
    private suspend fun finish(): Result {
        storage.deleteOrphans(dao.downloadedBookTracks().mapNotNull { it.filePath })
        // If nothing is waiting anymore (not even after an error), nobody resumes a partial file.
        if (dao.countPendingBookDownloads() == 0) {
            storage.deleteParts()
        } else if (manualOnly) {
            entryPoint.bookDownloads().continueOnWifi()
        }
        return Result.success()
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
            setForeground(foregroundInfo(buildNotification(dao.countPendingBookDownloads(), progress)))
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
            .setContentTitle(applicationContext.getString(R.string.book_download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface BookEntryPoint {
        fun bookDao(): BookDao
        fun bookDownloader(): BookDownloader
        fun bookStorage(): BookStorage
        fun bookProgress(): BookDownloadProgress
        fun bookDownloads(): BookDownloads
    }

    internal companion object {
        /** Only download manually enqueued files; the job may run over mobile data. */
        const val KEY_MANUAL_ONLY = "manual_only"

        private const val TAG = "BookDownloadWorker"

        /** The same channel as for the music downloads. */
        private const val CHANNEL_ID = "downloads"
        private const val NOTIFICATION_ID = 2003
        private const val MAX_ATTEMPTS = 5
    }
}
