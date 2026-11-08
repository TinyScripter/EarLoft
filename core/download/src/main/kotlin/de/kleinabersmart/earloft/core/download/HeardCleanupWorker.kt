package de.kleinabersmart.earloft.core.download

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.work.ensurePeriodicWork
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
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, CleanupEntryPoint::class.java)
        val removed = entryPoint.episodeDownloads().removeExpiredHeard()
        if (removed > 0) Log.i(TAG, "$removed gehörte Folgen gelöscht")
        val orphans = entryPoint.episodeDownloads().deleteOrphans() + entryPoint.bookDownloads().deleteOrphans()
        if (orphans > 0) Log.i(TAG, "$orphans verwaiste Dateien von Folgen und Büchern gelöscht")
        return Result.success()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface CleanupEntryPoint {
        fun episodeDownloads(): EpisodeDownloads
        fun bookDownloads(): BookDownloads
    }

    companion object {
        private const val TAG = "HeardCleanupWorker"
        private const val WORK_NAME = "heard-episode-cleanup"

        suspend fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()
            WorkManager.getInstance(context).ensurePeriodicWork(WORK_NAME, HeardCleanupWorker::class.java, 1, TimeUnit.DAYS, constraints)
        }
    }
}
