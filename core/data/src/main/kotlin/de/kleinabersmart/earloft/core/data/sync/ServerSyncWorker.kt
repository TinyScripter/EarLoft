package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import de.kleinabersmart.earloft.core.model.SpokenMode
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
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.source.DataSourceRegistry
import de.kleinabersmart.earloft.core.data.work.ensurePeriodicWork
import java.util.concurrent.TimeUnit

/**
 * Periodic full sync of all servers (daily, weekly or monthly, [LibrarySettings.syncIntervalDays]) (mark-and-sweep only finds deleted songs this way).
 * Runs only on Wi-Fi (unmetered) and while charging, because large libraries query many pages.
 */
class ServerSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, SyncEntryPoint::class.java)
        // Hidden music (General → Sections): no sync with the servers.
        if (SpokenMode.MUSIC !in entryPoint.spokenSettings().areas.first()) return Result.success()
        val all = entryPoint.registry().servers()
        // A retry only for the servers that failed: a healthy one was otherwise scanned up to four times per run (review G37).
        val recently = System.currentTimeMillis() - RETRY_SKIP_MS
        val servers = if (runAttemptCount == 0) {
            all
        } else {
            all.filter { (entryPoint.sourceDao().get(it.sourceId)?.lastFullSyncAt ?: 0L) < recently }
        }
        // New means, as with a manual refresh: created after the start; nothing on the first read of a server.
        val baseline = entryPoint.newSongs().begin(servers.map { it.sourceId })
        val allSucceeded = servers
            .map { entryPoint.syncer().syncSafely(it) }
            .all { it }
        val albums = entryPoint.newSongs().newAlbums(baseline)
        if (albums.isNotEmpty()) entryPoint.notifier().show(NewContent.Music(albums))
        return if (allSucceeded || runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface SyncEntryPoint {
        fun syncer(): LibrarySyncer
        fun registry(): DataSourceRegistry
        fun newSongs(): NewSongsCounter
        fun notifier(): NewContentNotifier
        fun spokenSettings(): SpokenSettings
        fun sourceDao(): SourceDao
    }

    companion object {
        private const val WORK_NAME = "server-sync"
        private const val MAX_ATTEMPTS = 3

        /** Fully synced this recently: done in this period, not part of a retry. */
        private const val RETRY_SKIP_MS = 12 * 60 * 60 * 1000L

        /** [days] = [LibrarySettings.SYNC_NEVER] ends the scheduling. */
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
