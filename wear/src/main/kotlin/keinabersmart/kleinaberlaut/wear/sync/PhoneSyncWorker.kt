package keinabersmart.kleinaberlaut.wear.sync

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

/**
 * Übernimmt Server und Manifest vom Handy ([PhoneLibrarySync]). Als Worker, weil der
 * `WatchListenerService` nur kurz läuft und ein großes Manifest länger braucht.
 */
class PhoneSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, PhoneSyncEntryPoint::class.java)
        return try {
            entryPoint.phoneLibrarySync().sync()
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Übernahme vom Handy fehlgeschlagen", e)
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

        /** Läuft gerade einer, folgt ein weiterer – er liest dann den neuesten Stand. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<PhoneSyncWorker>().build(),
            )
        }
    }
}
