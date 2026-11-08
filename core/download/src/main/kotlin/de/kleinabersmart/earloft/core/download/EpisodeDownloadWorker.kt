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
import de.kleinabersmart.earloft.core.database.dao.PodcastDao

/**
 * Arbeitet die Warteschlange der Folgen-Downloads nacheinander ab, neueste Folge zuerst. Aufbau wie beim
 * [DownloadWorker]: Vordergrund-Worker mit Fortschritt, Netzfehler stellen zurück und versuchen es mit Backoff erneut.
 */
class EpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, EpisodeEntryPoint::class.java)
    private val dao = entryPoint.podcastDao()
    private val downloader = entryPoint.episodeDownloader()
    private val storage = entryPoint.episodeStorage()
    private val progress = entryPoint.episodeProgress()

    override suspend fun doWork(): Result {
        dao.requeueRunningDownloads()
        if (dao.countPendingDownloads() == 0) return finish()
        startForeground(progress = null)

        while (true) {
            val next = dao.nextQueuedDownload() ?: break
            val throttle = ProgressThrottle()
            progress.report(next.bookId, 0, null)
            val outcome = try {
                downloader.download(next) { done, total ->
                    progress.report(next.bookId, done, total)
                    val percent = throttle.next(done, total)
                    if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
                }
            } finally {
                progress.clear(next.bookId)
            }
            when (outcome) {
                is DownloadOutcome.Retry -> {
                    Log.w(TAG, "Folge ${next.bookId} unterbrochen (Versuch ${runAttemptCount + 1})", outcome.cause)
                    if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                        dao.requeueDownload(next.bookId)
                        return Result.retry()
                    }
                    dao.markDownloadFailed(next.bookId)
                }
                is DownloadOutcome.Failed -> Log.w(TAG, "Folge ${next.bookId} fehlgeschlagen: ${outcome.reason}")
                DownloadOutcome.Done, DownloadOutcome.Cancelled -> Unit
            }
        }
        return finish()
    }

    /** Räumt Dateien weg, zu denen es keinen Eintrag mehr gibt (Abo beendet, Folge aus dem Feed gefallen). */
    private suspend fun finish(): Result {
        storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, emptyList())
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /** Ohne Vordergrund-Erlaubnis (Start aus dem Hintergrund) wird einfach normal weitergeladen. */
    /**
     * Aus dem Hintergrund (z. B. Wiederholung nach Backoff) verweigert das System den Vordergrunddienst; dann wird normal
     * weitergeladen und in diesem Lauf nicht erneut gefragt (sonst bei jedem Prozent eine Ausnahme samt Stacktrace).
     */
    private var foregroundDenied = false

    private suspend fun startForeground(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(dao.countPendingDownloads(), progress)))
        } catch (e: IllegalStateException) {
            foregroundDenied = true
            Log.i(TAG, "Kein Vordergrund-Start möglich, lade im Hintergrund (${e.javaClass.simpleName})")
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
            .setContentTitle(applicationContext.getString(R.string.episode_download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EpisodeEntryPoint {
        fun podcastDao(): PodcastDao
        fun episodeDownloader(): EpisodeDownloader
        fun episodeStorage(): EpisodeStorage
        fun episodeProgress(): EpisodeDownloadProgress
    }

    private companion object {
        const val TAG = "EpisodeDownloadWorker"

        /** Derselbe Kanal wie bei den Musik-Downloads. */
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2002
        const val MAX_ATTEMPTS = 5
    }
}
