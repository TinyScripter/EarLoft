package de.kleinabersmart.tinyamp.core.data.sync

import de.kleinabersmart.tinyamp.core.data.spoken.SpokenSettings
import de.kleinabersmart.tinyamp.core.model.SpokenMode
import kotlinx.coroutines.flow.first
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.tinyamp.core.data.source.DataSourceRegistry
import de.kleinabersmart.tinyamp.core.data.work.ensurePeriodicWork
import java.util.concurrent.TimeUnit

/**
 * Regelmäßiger Vollabgleich aller Server (täglich, wöchentlich oder monatlich, [LibrarySettings.syncIntervalDays]) (Mark-and-Sweep findet gelöschte Songs nur so).
 * Läuft nur im WLAN (ungetaktet) und beim Laden, weil große Bibliotheken viele Seiten abfragen.
 */
class ServerSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        // Ausgeblendete Musik (Allgemein → Bereiche): kein Abgleich mit den Servern.
        if (SpokenMode.MUSIC !in entryPoint.spokenSettings().areas.first()) return Result.success()
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
        fun spokenSettings(): SpokenSettings
    }

    companion object {
        private const val WORK_NAME = "server-sync"
        private const val MAX_ATTEMPTS = 3

        /** [days] = [LibrarySettings.SYNC_NEVER] beendet die Planung. */
        suspend fun schedule(context: Context, days: Int) {
            if (days <= LibrarySettings.SYNC_NEVER) {
                WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
                return
            }
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresCharging(true)
                .build()
            WorkManager.getInstance(context).ensurePeriodicWork(WORK_NAME, ServerSyncWorker::class.java, days.toLong(), TimeUnit.DAYS, constraints)
        }
    }
}
