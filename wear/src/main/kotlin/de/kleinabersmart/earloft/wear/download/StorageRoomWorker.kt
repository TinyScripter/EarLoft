package de.kleinabersmart.earloft.wear.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.download.DownloadStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * After a transfer ran into a full storage ([WatchState.setStorageFullError]): waits for room and then lets the planner queue
 * what was left out again, within its budget. Android announces freed space only through WorkManager's "storage not low"
 * constraint, and only with a delay (right after the transfer broke off the constraint was still met on the emulator). Its
 * threshold is lower than the free space the settings keep ([downloadBudget]), so while the budget is not positive the worker
 * looks again later: first after 10 minutes, then each wait twice as long up to 6 hours ([storageRecheckDelayMs]), as every
 * run may start the process. With low storage the wait lasts until Android reports room. A new full storage starts again at
 * 10 minutes. `WatchStatusPublisher` plans as well whenever it reports, this worker covers freed space while nothing else
 * happens on the watch.
 */
class StorageRoomWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, StorageRoomEntryPoint::class.java)
        val state = entryPoint.state()
        if (!state.isStorageFull) return Result.success()
        val free = withContext(Dispatchers.IO) { entryPoint.storage().freeBytes() }
        val done = entryPoint.wearDao().getDoneBytes() + entryPoint.bookDao().trackTotals().doneBytes
        val budget = downloadBudget(state.settings.value, free, done, reservedBytes = 0)
        if (budget <= 0) {
            val delayMs = storageRecheckDelayMs(inputData.getLong(KEY_LAST_DELAY_MS, 0L))
            Log.i(TAG, "Storage still below the reserve (budget $budget bytes), looking again in ${delayMs / 60_000} min")
            enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE, delayMs)
            return Result.success()
        }
        Log.i(TAG, "Room again ($budget bytes), planning the downloads left out")
        state.clearStorageFullError()
        entryPoint.planner().plan()
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface StorageRoomEntryPoint {
        fun state(): WatchState
        fun storage(): DownloadStorage
        fun wearDao(): WearDao
        fun bookDao(): BookDao
        fun planner(): WearDownloadPlanner
    }

    companion object {
        private const val TAG = "StorageRoomWorker"
        private const val WORK_NAME = "wear-storage-room"

        private const val KEY_LAST_DELAY_MS = "last_delay_ms"

        /** From the download worker when the storage ran full; replaces a waiting check and starts the waits anew. */
        fun schedule(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE, delayMs = 0)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<StorageRoomWorker>()
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setInputData(workDataOf(KEY_LAST_DELAY_MS to delayMs))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}

private const val FIRST_RECHECK_MS = 10 * 60 * 1000L
private const val LONGEST_RECHECK_MS = 6 * 60 * 60 * 1000L

/** The wait before the next look for room: 10 minutes after [lastDelayMs] of 0, else twice the last wait, at most 6 hours. */
internal fun storageRecheckDelayMs(lastDelayMs: Long): Long =
    if (lastDelayMs < FIRST_RECHECK_MS) FIRST_RECHECK_MS else (minOf(lastDelayMs, LONGEST_RECHECK_MS) * 2).coerceAtMost(LONGEST_RECHECK_MS)
