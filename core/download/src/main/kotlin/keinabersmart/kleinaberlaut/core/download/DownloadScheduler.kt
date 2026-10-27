package keinabersmart.kleinaberlaut.core.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Startet die Abarbeitung der Download-Warteschlange. */
interface DownloadScheduler {
    /**
     * Ein laufender Worker holt neu eingereihte Downloads selbst ab; der angehängte Auftrag deckt
     * den Fall ab, dass er gerade endet. Mit [replace] wird ein wartender Auftrag ersetzt, z. B.
     * nach Ändern der WLAN-Einstellung (ein laufender wird dabei abgebrochen und neu gestartet).
     */
    suspend fun enqueue(replace: Boolean = false)
}

/** Startet den [DownloadWorker]; es läuft immer höchstens einer. */
@Singleton
internal class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: DownloadSettings,
) : DownloadScheduler {

    override suspend fun enqueue(replace: Boolean) {
        val wifiOnly = settings.wifiOnly.first()
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "downloads"
    }
}
