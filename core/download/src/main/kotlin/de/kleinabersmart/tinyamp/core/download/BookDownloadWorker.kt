package de.kleinabersmart.tinyamp.core.download

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
import de.kleinabersmart.tinyamp.core.database.dao.BookDao
import de.kleinabersmart.tinyamp.core.model.TrackDownload

/**
 * Arbeitet die Warteschlange der Buch-Downloads Datei für Datei ab. Aufbau wie beim [EpisodeDownloadWorker]:
 * Vordergrund-Worker mit Fortschritt, Netzfehler stellen zurück und versuchen es mit Backoff erneut, dann an der
 * Stelle, an der die Teildatei endet.
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

    override suspend fun doWork(): Result {
        dao.requeueRunningBookDownloads()
        if (dao.countPendingBookDownloads() == 0) return finish()
        // Teildateien eines früheren, abgebrochenen Laufs bleiben liegen: der Downloader setzt dort fort.
        startForeground(progress = null)

        while (true) {
            val next = dao.nextQueuedBookTrack() ?: break
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
                    Log.w(TAG, "Buch ${next.bookId} Datei ${next.trackIndex} unterbrochen (Versuch ${runAttemptCount + 1})", outcome.cause)
                    if (runAttemptCount + 1 < MAX_ATTEMPTS) {
                        dao.requeueBookTrack(next.bookId, next.trackIndex)
                        return Result.retry()
                    }
                    dao.markBookDownloadFailed(next.bookId)
                }
                is DownloadOutcome.Failed -> Log.w(TAG, "Buch ${next.bookId} fehlgeschlagen: ${outcome.reason}")
                DownloadOutcome.Done, DownloadOutcome.Cancelled -> Unit
            }
        }
        return finish()
    }

    /** Räumt Dateien weg, zu denen es keinen Eintrag mehr gibt (Buch vom Server gelöscht oder neu eingelesen). */
    private suspend fun finish(): Result {
        storage.deleteOrphans(dao.downloadedBookTracks().mapNotNull { it.filePath })
        // Wartet nichts mehr (auch nicht nach einem Fehler), setzt keiner eine Teildatei fort.
        if (dao.countPendingBookDownloads() == 0) storage.deleteParts()
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
            setForeground(foregroundInfo(buildNotification(dao.countPendingBookDownloads(), progress)))
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
    }

    private companion object {
        const val TAG = "BookDownloadWorker"

        /** Derselbe Kanal wie bei den Musik-Downloads. */
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2003
        const val MAX_ATTEMPTS = 5
    }
}
