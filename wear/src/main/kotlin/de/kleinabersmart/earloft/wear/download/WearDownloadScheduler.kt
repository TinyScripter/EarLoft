package de.kleinabersmart.earloft.wear.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.download.DownloadScheduler
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.await
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
        // Only a waiting job is replaced. A running one takes new entries from the database itself; replacing it would start the
        // song it is loading from 0 again and restart the foreground service and the Wi-Fi request with every new selection
        // (review4 T63). Then appending is enough.
        val replaceNow = replace && !isRunning()
        // A replaced job starts from scratch, including the attempts without Wi-Fi (like a fresh runAttemptCount used to).
        if (replaceNow) state.clearRetries()
        enqueue(
            requireCharging = state.settings.value.requireCharging,
            policy = if (replaceNow) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE,
        )
    }

    /**
     * Also replaces a running job: it reads the quality once and keeps the charger constraint it was started with, so after a change
     * on the phone it would load the rest of its queue under the old setting (review4 8.6 Y3).
     */
    override suspend fun restart() {
        state.clearRetries()
        enqueue(requireCharging = state.settings.value.requireCharging, policy = ExistingWorkPolicy.REPLACE)
    }

    private suspend fun isRunning(): Boolean =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).await().any { it.state == WorkInfo.State.RUNNING }

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

    /**
     * The download job that starts next only starts on the charger because Wi-Fi or the phone was missing repeatedly
     * ([enqueueOnCharger]); the setting of the phone is another reason. Without it the watch would keep asking for Wi-Fi although
     * turning it on changes nothing until it is on power (review4 T58). Read from WorkManager: with jobs appended to a running one
     * a flag described the one appended last, not the next (8.6 Y28).
     */
    val nextJobOnCharger: Flow<Boolean> = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
        .map(::startsNextOnCharger)
        .distinctUntilChanged()

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
            .apply { if (onCharger) addTag(TAG_ON_CHARGER) }
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    internal companion object {
        const val WORK_NAME = "wear-downloads"
        const val TAG_ON_CHARGER = "on-charger"

        /** While a job runs nothing waits; otherwise the job that starts next (the enqueued one, later ones are blocked behind it). */
        fun startsNextOnCharger(jobs: List<WorkInfo>): Boolean =
            jobs.none { it.state == WorkInfo.State.RUNNING } &&
                jobs.firstOrNull { it.state == WorkInfo.State.ENQUEUED }?.tags?.contains(TAG_ON_CHARGER) == true
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface WearDownloadModule {
    @Binds
    fun bindScheduler(scheduler: WearDownloadScheduler): DownloadScheduler
}
