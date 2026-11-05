package de.kleinabersmart.tinyamp.wear

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
import de.kleinabersmart.tinyamp.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import de.kleinabersmart.tinyamp.core.download.R as DownloadR

/**
 * Hält den Prozess wach, solange das Handy Dateien an die Uhr schickt (Wear-Konzept 8.4).
 *
 * Die Play-Dienste rufen [PhoneWearListenerService.onChannelOpened] auf und binden den Dienst danach nicht weiter. Liegt die
 * App nicht vorn, ist der Prozess damit *cached*: Android friert ihn nach wenigen Sekunden ein und beendet ihn, sobald die
 * Play-Dienste ihn ansprechen – mitten in der Übertragung (Review N1). Ein laufender Auftrag ([WatchTransferWorker]) hält
 * den Prozess aus dem Cache. Kommt er zu spät oder gar nicht (Kontingent erschöpft), setzt die Uhr beim nächsten Versuch fort.
 */
@Singleton
class WatchFileTransfers @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    private val active = MutableStateFlow(0)

    /** Anzahl der laufenden Übertragungen. */
    val running: StateFlow<Int> = active

    /** Führt [send] aus und hält den Prozess so lange mit dem Auftrag wach. */
    suspend fun <T> track(send: suspend () -> T): T {
        active.update { it + 1 }
        WatchTransferWorker.enqueue(context)
        try {
            return send()
        } finally {
            active.update { it - 1 }
        }
    }
}

/**
 * Läuft, solange [WatchFileTransfers] Übertragungen zählt, und danach noch [GRACE_MS] (die Uhr fragt die nächste Datei
 * gleich hinterher). Tut sonst nichts: Allein dass ein Auftrag läuft, hält den Prozess aus dem Cache.
 */
class WatchTransferWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val transfers = EntryPointAccessors.fromApplication(applicationContext, TransferEntryPoint::class.java).watchFileTransfers()
        val finished = withTimeoutOrNull(MAX_RUN_MS) {
            while (true) {
                transfers.running.first { it == 0 }
                // Kommt in der Karenz eine neue Übertragung, weiter warten; sonst fertig.
                withTimeoutOrNull(GRACE_MS) { transfers.running.first { it > 0 } } ?: break
            }
        }
        if (finished == null) Log.w(TAG, "Übertragung zur Uhr läuft länger als ${MAX_RUN_MS / 60_000} min, Auftrag endet")
        return Result.success()
    }

    /** Nur unter Android 12 nötig: Dort läuft ein vorgezogener Auftrag als Vordergrunddienst. */
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

        /** Obergrenze, falls eine Übertragung hängt; die Uhr bricht selbst nach 30 s ohne Daten ab. */
        private const val MAX_RUN_MS = 60 * 60_000L

        /** Läuft der Auftrag schon, bleibt er (er wartet auf alle Übertragungen). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WatchTransferWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
