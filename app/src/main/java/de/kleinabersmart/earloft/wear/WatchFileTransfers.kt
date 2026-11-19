package de.kleinabersmart.earloft.wear

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import de.kleinabersmart.earloft.core.download.R as DownloadR

/**
 * Keeps the process awake while the phone sends files to the watch (Wear concept 8.4).
 *
 * Play services call [PhoneWearListenerService.onChannelOpened] and then no longer keep the service bound. If the
 * app isn't in the foreground, the process is thereby *cached*: Android freezes it after a few seconds and kills it as soon as
 * Play services address it – in the middle of the transfer (review N1). A running work request ([WatchTransferWorker]) keeps
 * the process out of the cache. If it comes too late or not at all (quota exhausted), the watch resumes on the next attempt.
 */
@Singleton
class WatchFileTransfers @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val active = MutableStateFlow(0)

    /** Number of running transfers. */
    val running: StateFlow<Int> = active

    /** The work request is running and notices new transfers by itself. */
    internal val workerRunning = AtomicBoolean(false)

    /** A work request is enqueued but not started yet. */
    internal val workerQueued = AtomicBoolean(false)

    /** Runs [send] and keeps the process awake with the work request for that long. */
    suspend fun <T> track(send: suspend () -> T): T {
        active.update { it + 1 }
        // Count first, then check: if the work request is just finishing, it either sees the new transfer, or here
        // [workerRunning] is already off and a new one follows (otherwise `KEEP` would discard it, and the transfer would run without one).
        if (!workerRunning.get() && workerQueued.compareAndSet(false, true)) WatchTransferWorker.enqueue(context)
        try {
            return send()
        } finally {
            active.update { it - 1 }
        }
    }
}

/**
 * Runs as long as [WatchFileTransfers] counts transfers, and then another [GRACE_MS] (the watch requests the next file
 * right after). Does nothing else: merely having a work request running keeps the process out of the cache.
 */
class WatchTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val transfers = EntryPointAccessors.fromApplication(applicationContext, TransferEntryPoint::class.java).watchFileTransfers()
        transfers.workerRunning.set(true)
        transfers.workerQueued.set(false)
        val finished = withTimeoutOrNull(MAX_RUN_MS) {
            while (true) {
                transfers.running.first { it == 0 }
                // If a new transfer arrives during the grace period, keep waiting; otherwise done.
                if (withTimeoutOrNull(GRACE_MS) { transfers.running.first { it > 0 } } != null) continue
                transfers.workerRunning.set(false)
                if (transfers.running.value == 0) break
                transfers.workerRunning.set(true)
            }
        }
        transfers.workerRunning.set(false)
        if (finished == null) Log.w(TAG, "Übertragung zur Uhr läuft länger als ${MAX_RUN_MS / 60_000} min, Auftrag endet")
        return Result.success()
    }

    /** Only needed below Android 12: there an expedited work request runs as a foreground service. */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val notification = buildNotification()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        NotificationManagerCompat.from(applicationContext).createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.watch_transfer_channel))
                .build(),
        )
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(DownloadR.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.watch_transfer_notification))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface TransferEntryPoint {
        fun watchFileTransfers(): WatchFileTransfers
    }

    companion object {
        private const val TAG = "WatchTransferWorker"
        private const val WORK_NAME = "watch-file-transfer"
        private const val CHANNEL_ID = "watch_transfer"
        private const val NOTIFICATION_ID = 2101
        private const val GRACE_MS = 15_000L

        /** Upper limit in case a transfer hangs; the watch itself aborts after 30 s without data. */
        private const val MAX_RUN_MS = 60 * 60_000L

        /**
         * Only called when no work request is running or the running one is just finishing ([WatchFileTransfers.track]); this one
         * replaces it.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchTransferWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
