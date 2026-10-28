package keinabersmart.kleinaberlaut.wear.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
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
import keinabersmart.kleinaberlaut.core.data.repository.ArtworkRepository
import keinabersmart.kleinaberlaut.core.data.repository.CoverFiles
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.entity.DownloadEntity
import keinabersmart.kleinaberlaut.core.download.DownloadOutcome
import keinabersmart.kleinaberlaut.core.download.DownloadProfile
import keinabersmart.kleinaberlaut.core.download.DownloadStorage
import keinabersmart.kleinaberlaut.core.download.Downloader
import keinabersmart.kleinaberlaut.core.wearsync.WearQuality
import keinabersmart.kleinaberlaut.wear.R
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lädt die eingereihten Songs der Auswahlen vom Server (Wear-Konzept 8.2): nur über ein ausdrücklich
 * angefordertes WLAN, nach Einstellung nur am Ladegerät (Bedingung des Auftrags, siehe [WearDownloadScheduler]).
 * Ohne WLAN versucht WorkManager es später mit Backoff erneut. Songs, die nur das Handy hat, und Songs, bei
 * denen der Server dreimal gescheitert ist, kommen über das Handy ([PhoneFileFetcher], Konzept 8.3/8.4).
 */
class WearDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, WearDownloadEntryPoint::class.java)
    private val downloadDao = entryPoint.downloadDao()
    private val downloader = entryPoint.downloader()
    private val storage = entryPoint.storage()
    private val state = entryPoint.state()
    private val artwork = entryPoint.artwork()
    private val coverFiles = entryPoint.coverFiles()
    private val phoneFetcher = entryPoint.phoneFetcher()

    override suspend fun doWork(): Result {
        downloadDao.requeueRunning()
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        if (downloadDao.countPending() == 0 && !coversMissing) return finish()
        startForeground()

        // Songs, die nur das Handy hat, brauchen kein WLAN.
        if (!drainPhoneQueue()) return Result.retry()

        var lease: WifiLease? = null
        try {
            val profile = state.settings.value.quality.toProfile()
            while (true) {
                val next = downloadDao.nextQueuedFromServers() ?: break
                val uid = next.sourceUid
                if (state.hasServerFailedTooOften(uid)) {
                    // Der Server hat es schon mehrmals nicht geschafft (Wechsel an anderer Stelle, später erneut versucht).
                    if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    continue
                }
                lease = lease ?: entryPoint.wifi().acquire()
                if (lease == null) {
                    // Kein WLAN zählt nicht als Fehlversuch des Servers: Sonst käme die ganze Auswahl über Bluetooth.
                    Log.i(TAG, "Kein WLAN, später erneut (Versuch ${runAttemptCount + 1})")
                    return Result.retry()
                }
                var lastPercent = -1
                val outcome = downloader.download(next, profile, lease.network) { done, total ->
                    val percent = total?.let { (done * 100 / it).toInt() } ?: -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        notify(percent.takeIf { it >= 0 })
                    }
                }
                when (outcome) {
                    is DownloadOutcome.Retry -> {
                        val failures = state.recordServerFailure(uid)
                        // Ein nicht erreichbarer Server trifft alle wartenden Songs von ihm: Sonst brauchte jeder drei eigene
                        // Läufe, und der Backoff von WorkManager wüchse dabei weiter.
                        downloadDao.getSongSource(uid)?.sourceId?.let { sourceId ->
                            state.recordServerFailures(downloadDao.queuedUidsOfSource(sourceId).filter { it != uid })
                        }
                        Log.w(TAG, "Download $uid unterbrochen (Fehlversuch $failures von ${WatchState.SERVER_ATTEMPTS})", outcome.cause)
                        if (failures < WatchState.SERVER_ATTEMPTS) {
                            downloadDao.requeue(uid)
                            return Result.retry()
                        }
                        if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    }
                    is DownloadOutcome.Failed -> {
                        Log.w(TAG, "Download $uid fehlgeschlagen: ${outcome.reason}")
                        // Datei weg, Anmeldung abgelehnt o. Ä.: Vielleicht hat das Handy sie.
                        state.setLastError(outcome.reason)
                        if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    }
                    DownloadOutcome.Done -> {
                        state.clearServerFailures(uid)
                        state.setLastError(null)
                    }
                    DownloadOutcome.Cancelled -> Unit
                }
            }
        } finally {
            lease?.close()
        }
        cacheCovers()
        return finish()
    }

    /** Lädt alle wartenden Songs, die nur das Handy hat. @return `false`, wenn das Handy fehlt und es später erneut versucht werden soll. */
    private suspend fun drainPhoneQueue(): Boolean {
        while (true) {
            val next = downloadDao.nextQueuedFromPhone() ?: return true
            if (!viaPhone(next, serverFailed = false)) return false
        }
    }

    /**
     * Holt [download] im Original vom Handy (Wear-Konzept 8.4). [serverFailed]: Der Server hat es nicht geschafft
     * (sonst gibt es nur das Handy), das bestimmt den Fehlertext, wenn auch das Handy nichts hat.
     * @return `false`, wenn das Handy nicht erreichbar war; der Eintrag wartet dann weiter.
     */
    private suspend fun viaPhone(download: DownloadEntity, serverFailed: Boolean): Boolean {
        val uid = download.sourceUid
        // Zurück auf QUEUED: aus RUNNING (Netzfehler) bzw. FAILED (Server hat abgelehnt).
        downloadDao.requeue(uid)
        downloadDao.retryFailed(listOf(uid))
        var lastPercent = -1
        return when (val outcome = phoneFetcher.fetch(download) { done, total ->
            val percent = total?.let { (done * 100 / it).toInt() } ?: -1
            if (percent != lastPercent) {
                lastPercent = percent
                notify(percent.takeIf { it >= 0 })
            }
        }) {
            DownloadOutcome.Done -> {
                state.clearServerFailures(uid)
                state.setLastError(null)
                true
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "Handy hat $uid nicht: ${outcome.reason}")
                state.setLastError(
                    applicationContext.getString(
                        if (serverFailed) R.string.download_error_server_no_phone_copy else R.string.download_error_phone_no_copy,
                    ),
                )
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Handy für $uid nicht erreichbar (Versuch ${runAttemptCount + 1})", outcome.cause)
                downloadDao.requeue(uid)
                state.setLastError(applicationContext.getString(R.string.download_error_phone_unreachable))
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /** Je Album mit geladenen Songs einmal das Cover als Datei (Wear-Konzept 8.2). Fehlt es, geht die Wiedergabe trotzdem. */
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

    private suspend fun finish(): Result {
        storage.deleteOrphans(downloadDao.getAll())
        coverFiles.retainOnly(downloadDao.getAlbumIdsWithDoneDownload().toSet())
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /** Ohne Vordergrund-Erlaubnis (Start aus dem Hintergrund) wird einfach normal weitergeladen. */
    private suspend fun startForeground() = notify(progress = null)

    private suspend fun notify(progress: Int?) {
        try {
            setForeground(foregroundInfo(buildNotification(downloadDao.countPending(), progress)))
        } catch (e: IllegalStateException) {
            Log.i(TAG, "Kein Vordergrund-Start möglich, lade im Hintergrund", e)
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

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
            .setSmallIcon(android.R.drawable.stat_sys_download)
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
    internal interface WearDownloadEntryPoint {
        fun downloadDao(): DownloadDao
        fun downloader(): Downloader
        fun storage(): DownloadStorage
        fun wifi(): WifiNetwork
        fun state(): WatchState
        fun artwork(): ArtworkRepository
        fun coverFiles(): CoverFiles
        fun phoneFetcher(): PhoneFileFetcher
    }

    private companion object {
        const val TAG = "WearDownloadWorker"
        const val CHANNEL_ID = "wear-downloads"
        const val NOTIFICATION_ID = 2002
        const val COVER_SIZE_PX = 400
    }
}

private fun WearQuality.toProfile(): DownloadProfile = when (this) {
    WearQuality.OPUS_128 -> DownloadProfile.OPUS_128
    WearQuality.ORIGINAL -> DownloadProfile.ORIGINAL
}
