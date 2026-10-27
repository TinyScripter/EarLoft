package keinabersmart.kleinaberlaut.core.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.source.DataSourceRegistry
import java.util.concurrent.TimeUnit

/**
 * Täglicher Vollabgleich aller Server (Mark-and-Sweep findet gelöschte Songs nur so).
 * Läuft nur im WLAN (ungetaktet) und beim Laden, weil große Bibliotheken viele Seiten abfragen.
 */
class ServerSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        val allSucceeded = entryPoint.registry().servers()
            .map { entryPoint.syncer().syncSafely(it) }
            .all { it }
        return if (allSucceeded || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SyncEntryPoint {
        fun syncer(): LibrarySyncer
        fun registry(): DataSourceRegistry
    }

    companion object {
        private const val WORK_NAME = "server-sync"
        private const val MAX_ATTEMPTS = 3

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ServerSyncWorker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .setRequiresCharging(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
