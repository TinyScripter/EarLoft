package keinabersmart.kleinaberlaut.core.data.podcast

import android.content.Context
import androidx.work.BackoffPolicy
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
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält die Abos aktuell: ein periodischer Auftrag im eingestellten Abstand (nur im WLAN, außer „Mobilfunk“ ist erlaubt),
 * beim Start alles, was älter als dieser Abstand ist, und auf Anforderung alles.
 */
@Singleton
class PodcastRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val dao: PodcastDao,
    private val settings: PodcastSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(settings.refreshHours, settings.useMobileData) { hours, mobile -> hours to mobile }
                .distinctUntilChanged()
                .collect { (hours, mobile) -> schedule(hours, mobile) }
        }
        scope.launch {
            val hours = settings.refreshHours.first()
            if (hours == 0) return@launch
            val stale = System.currentTimeMillis() - hours * HOUR_MS
            dao.feeds().filter { it.lastRefreshAt < stale }.forEach { repository.refresh(it.feedId) }
        }
    }

    fun refreshAll() {
        scope.launch { repository.refreshAll() }
    }

    private fun schedule(hours: Int, mobile: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (hours == 0) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .build()
        val request = PeriodicWorkRequestBuilder<PodcastRefreshWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private companion object {
        const val WORK_NAME = "podcast-refresh"
        const val HOUR_MS = 60 * 60 * 1000L
    }
}

class PodcastRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java).repository()
        return if (repository.refreshAll()) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun repository(): PodcastRepository
    }
}
