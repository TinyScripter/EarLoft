package de.kleinabersmart.earloft.wear.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
 * looks again after [RECHECK_MS]; with low storage that wait lasts until Android reports room. `WatchStatusPublisher` does the
 * same whenever it reports, this worker covers freed space while nothing else happens on the watch.
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
            Log.i(TAG, "Storage still below the reserve (budget $budget bytes), looking again later")
            enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE, RECHECK_MS)
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

        /** At least this long until the next look; a run without room only reads the free space. */
        private const val RECHECK_MS = 10 * 60 * 1000L

        /** From the download worker when the storage ran full; replaces a waiting check. */
        fun schedule(context: Context) = enqueue(context, ExistingWorkPolicy.REPLACE, delayMs = 0)

        private fun enqueue(context: Context, policy: ExistingWorkPolicy, delayMs: Long) {
            val request = OneTimeWorkRequestBuilder<StorageRoomWorker>()
                .setConstraints(Constraints.Builder().setRequiresStorageNotLow(true).build())
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
        }
    }
}
