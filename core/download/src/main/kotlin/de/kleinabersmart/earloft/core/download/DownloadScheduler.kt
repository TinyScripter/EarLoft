package de.kleinabersmart.earloft.core.download

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

/** Starts processing the download queue. */
interface DownloadScheduler {
    /**
     * A running worker picks up newly enqueued downloads itself; the appended job covers
     * the case that it is just finishing. With [replace] a waiting job is replaced, e.g.
     * after changing the Wi-Fi setting (a running one is cancelled and restarted).
     */
    suspend fun enqueue(replace: Boolean = false)

    /**
     * After a process death: only enqueue if no job is waiting or running. If the worker itself started the process,
     * [enqueue] would otherwise append a second one, which then starts with an empty queue.
     */
    suspend fun resume() = enqueue()
}

/**
 * Starts the [DownloadWorker]; at most one ever runs. The phone app binds it as
 * [DownloadScheduler], the watch has its own.
 */
@Singleton
class WorkManagerDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: DownloadSettings,
) : DownloadScheduler {

    override suspend fun enqueue(replace: Boolean) =
        enqueue(if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE)

    override suspend fun resume() = enqueue(ExistingWorkPolicy.KEEP)

    private suspend fun enqueue(policy: ExistingWorkPolicy) {
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
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "downloads"
    }
}
