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
import de.kleinabersmart.tinyamp.core.data.repository.ArtworkRepository
import de.kleinabersmart.tinyamp.core.data.repository.CoverFiles
import de.kleinabersmart.tinyamp.core.database.dao.DownloadDao
import kotlinx.coroutines.CancellationException

/**
 * Arbeitet die Download-Warteschlange nacheinander ab (Konzept 5.3) und läuft dabei als
 * Vordergrund-Worker mit Fortschrittsbenachrichtigung. Netzfehler stellen den Download zurück und
 * lassen WorkManager es mit Backoff erneut versuchen; nach [MAX_ATTEMPTS] gilt er als fehlgeschlagen.
 */
class DownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, DownloadEntryPoint::class.java)
    private val downloadDao = entryPoint.downloadDao()
    private val downloader = entryPoint.downloader()
    private val storage = entryPoint.storage()
    private val artwork = entryPoint.artwork()
    private val coverFiles = entryPoint.coverFiles()

    override suspend fun doWork(): Result {
        downloadDao.requeueRunning()
        if (downloadDao.countPending() == 0) return finish()
        startForeground(progress = null)

        while (true) {
            val next = downloadDao.nextQueued() ?: break
            val throttle = ProgressThrottle()
            val outcome = downloader.download(next) { done, total ->
                val percent = throttle.next(done, total)
                if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
            }
            when (outcome) {
                is DownloadOutcome.Retry -> {
                    Log.w(TAG, "Download ${next.sourceUid} unterbrochen (Versuch ${runAttemptCount + 1})", outcome.cause)
                    // Abgelehnte Zugangsdaten ändern sich durch Warten nicht; sonst hielte der Song die Warteschlange auf.
                    if (!outcome.isAuthRejection && runAttemptCount + 1 < MAX_ATTEMPTS) {
                        downloadDao.requeue(next.sourceUid)
                        return Result.retry()
                    }
                    downloadDao.markFailed(next.sourceUid)
                    // Die übrigen Songs dieses Servers scheiterten genauso (bei „alles laden“ Tausende Anfragen); neue
                    // Zugangsdaten im Serverformular reihen sie wieder ein.
                    if (outcome.isAuthRejection) downloadDao.failQueuedOfSource(next.sourceUid.substringBefore(':'))
                }
                is DownloadOutcome.Failed -> Log.w(TAG, "Download ${next.sourceUid} fehlgeschlagen: ${outcome.reason}")
                DownloadOutcome.Done, DownloadOutcome.Cancelled -> Unit
            }
        }
        return finish()
    }

    private suspend fun finish(): Result {
        storage.deleteOrphans(downloadDao.getAll())
        cacheCovers()
        coverFiles.retainOnly(downloadDao.getAlbumIdsWithDoneDownload().toSet())
        return Result.success()
    }

    /**
     * Je Album mit geladenen Songs einmal das Cover als Datei: Es gehört zum Download, zählt nicht zum Cover-Cache und
     * bleibt, auch wenn alle Cover zusammen größer werden. Fehlt es, bleibt die Wiedergabe unberührt.
     */
    private suspend fun cacheCovers() {
        for (albumId in downloadDao.getAlbumIdsWithDoneDownload()) {
            try {
                artwork.cacheAlbumCover(albumId, COVER_SIZE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover für Album $albumId nicht ladbar", e)
            }
        }
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
            setForeground(foregroundInfo(buildNotification(downloadDao.countPending(), progress)))
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
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface DownloadEntryPoint {
        fun downloadDao(): DownloadDao
        fun downloader(): Downloader
        fun storage(): DownloadStorage
        fun artwork(): ArtworkRepository
        fun coverFiles(): CoverFiles
    }

    private companion object {
        const val TAG = "DownloadWorker"
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2001
        const val MAX_ATTEMPTS = 5
        const val COVER_SIZE_PX = 1024
    }
}
