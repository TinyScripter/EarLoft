package de.kleinabersmart.kleinaberlaut.core.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit

/**
 * Löscht einmal am Tag die Downloads zu Ende gehörter Folgen, deren Frist abgelaufen ist ([EpisodeDownloads.removeExpiredHeard]).
 * Läuft die App, prüft sie zusätzlich bei jeder Änderung; ohne den Auftrag geschähe das Aufräumen aber nur, solange der
 * Prozess zufällig lebt.
 */
class HeardCleanupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val removed = EntryPointAccessors.fromApplication(applicationContext, CleanupEntryPoint::class.java)
            .episodeDownloads()
            .removeExpiredHeard()
        if (removed > 0) Log.i(TAG, "$removed gehörte Folgen gelöscht")
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface CleanupEntryPoint {
        fun episodeDownloads(): EpisodeDownloads
    }

    companion object {
        private const val TAG = "HeardCleanupWorker"
        private const val WORK_NAME = "heard-episode-cleanup"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeardCleanupWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
