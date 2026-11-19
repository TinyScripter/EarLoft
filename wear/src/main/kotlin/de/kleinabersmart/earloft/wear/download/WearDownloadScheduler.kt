package de.kleinabersmart.earloft.wear.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts the [WearDownloadWorker]. Unlike on the phone there is no network constraint: the worker requests
 * Wi-Fi itself, because the phone's Bluetooth proxy also counts as a network for WorkManager (Wear concept 8.2).
 * Never on low battery; depending on the setting or after several attempts without Wi-Fi ([enqueueOnCharger]) only on the charger.
 * The worker schedules retries itself ([enqueueRetry]) instead of using WorkManager's backoff (Review W1).
 */
@Singleton
class WearDownloadScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val state: WatchState,
) : DownloadScheduler {

    override suspend fun enqueue(replace: Boolean) {
        // A replaced job starts from scratch, including the attempts without Wi-Fi (like a fresh runAttemptCount used to).
        if (replace) state.clearRetries()
        enqueue(
            requireCharging = state.settings.value.requireCharging,
            policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    /**
     * On every process start and sync: a waiting job stays – including one that only runs on the charger or is waiting for
     * its retry. Otherwise each append would start a new one with five fresh Wi-Fi attempts.
     */
    override suspend fun resume() {
        enqueue(requireCharging = state.settings.value.requireCharging, policy = ExistingWorkPolicy.KEEP)
    }

    /**
     * From the running worker when there was repeatedly no Wi-Fi or the phone was missing: continue only on the charger (the
     * watch is usually on Wi-Fi there) instead of turning on Wi-Fi every few minutes. It is appended, so it runs after the
     * current job; a new selection replaces it with a normal one again.
     */
    fun enqueueOnCharger() {
        state.clearRetries()
        enqueue(requireCharging = true, policy = ExistingWorkPolicy.APPEND_OR_REPLACE, onCharger = true)
    }

    /**
     * From the running worker: the same job again after [delayMs], appended to the current one (which then ends
     * successfully). A new job instead of `Result.retry()`: if the system stops it (e.g. during the brief disconnect of adaptive
     * charging), WorkManager reschedules it without a growing backoff (Review W1). [onCharger] keeps the current one's charging constraint.
     */
    fun enqueueRetry(delayMs: Long, onCharger: Boolean) {
        enqueue(
            requireCharging = onCharger || state.settings.value.requireCharging,
            policy = ExistingWorkPolicy.APPEND_OR_REPLACE,
            onCharger = onCharger,
            delayMs = delayMs,
        )
    }

    private fun enqueue(requireCharging: Boolean, policy: ExistingWorkPolicy, onCharger: Boolean = false, delayMs: Long = 0) {
        val request = OneTimeWorkRequestBuilder<WearDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .setRequiresCharging(requireCharging)
                    .setRequiresBatteryNotLow(true)
                    .build(),
            )
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(WearDownloadWorker.KEY_ON_CHARGER to onCharger))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "wear-downloads"
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WearDownloadModule {
    @Binds
    fun bindScheduler(scheduler: WearDownloadScheduler): DownloadScheduler
}
