package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Applies server and manifest from the phone ([PhoneLibrarySync]). As a worker because the
 * `WatchListenerService` only runs briefly and a large manifest takes longer.
 */
class PhoneSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, PhoneSyncEntryPoint::class.java)
        return try {
            if (entryPoint.phoneLibrarySync().sync() || runAttemptCount >= MAX_ATTEMPTS) {
                Result.success()
            } else {
                // An asset not readable yet (phone out of range, transfer running): again with the backoff (review5 P71).
                Log.w(TAG, "Asset from the phone not readable yet, trying again")
                Result.retry()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Sync from the phone failed", e)
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface PhoneSyncEntryPoint {
        fun phoneLibrarySync(): PhoneLibrarySync
    }

    companion object {
        private const val TAG = "PhoneSyncWorker"
        private const val WORK_NAME = "phone-sync"
        private const val MAX_ATTEMPTS = 3

        /** If one is currently running, another follows – it then reads the latest state. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PhoneSyncWorker>().build(),
            )
        }

        /** A run after [delayMs], when a held state from the phone applies by itself ([RemovalGuard]). */
        fun enqueueIn(context: Context, delayMs: Long) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                HELD_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<PhoneSyncWorker>().setInitialDelay(delayMs.coerceAtLeast(0), TimeUnit.MILLISECONDS).build(),
            )
        }

        private const val HELD_WORK_NAME = "phone-sync-held"
    }
}
