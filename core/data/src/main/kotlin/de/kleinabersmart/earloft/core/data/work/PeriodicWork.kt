package de.kleinabersmart.earloft.core.data.work

import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Schedules periodic work only if it's missing or its constraints or interval no longer match (review N3).
 *
 * `ExistingPeriodicWorkPolicy.UPDATE` replaces the JobScheduler job even with identical content and increments the
 * generation every time. At process start that cancelled exactly the job that had just started the process. `KEEP` alone,
 * on the other hand, would adopt neither changed settings (interval, network) nor new constraints after an app update. Hence
 * it compares and replaces with `UPDATE` only on a mismatch (the interval keeps running).
 *
 * Backoff and flex time are not compared: if they change in code, change the work name.
 *
 * @return `true` if it was rescheduled.
 */
suspend fun WorkManager.ensurePeriodicWork(
    name: String,
    workerClass: Class<out ListenableWorker>,
    repeatInterval: Long,
    unit: TimeUnit,
    constraints: Constraints,
    configure: PeriodicWorkRequest.Builder.() -> Unit = {},
): Boolean {
    val intervalMillis = unit.toMillis(repeatInterval)
    if (isScheduled(getWorkInfosForUniqueWorkFlow(name).first(), constraints, intervalMillis)) return false
    val request = PeriodicWorkRequest.Builder(workerClass, repeatInterval, unit)
        .setConstraints(constraints)
        .apply(configure)
        .build()
    enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    Log.d(TAG, "$name scheduled: every $repeatInterval $unit, $constraints")
    return true
}

/** Is there already an active job under the name with exactly these constraints and this interval? */
internal fun isScheduled(infos: List<WorkInfo>, constraints: Constraints, intervalMillis: Long): Boolean =
    infos.any { info ->
        !info.state.isFinished &&
            info.constraints == constraints &&
            info.periodicityInfo?.repeatIntervalMillis == intervalMillis
    }

private const val TAG = "PeriodicWork"
