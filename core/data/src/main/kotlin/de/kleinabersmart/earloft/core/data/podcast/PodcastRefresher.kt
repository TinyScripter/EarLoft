package de.kleinabersmart.earloft.core.data.podcast

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvent
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvents
import de.kleinabersmart.earloft.core.data.refresh.RefreshKind
import de.kleinabersmart.earloft.core.data.spoken.LocalPodcasts
import de.kleinabersmart.earloft.core.data.work.ensurePeriodicWork
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps subscriptions up to date: a periodic job at the configured interval (Wi-Fi only, unless "mobile data" is allowed),
 * on opening the app everything older than this interval ([refreshStale], same network rule), and on request
 * everything. Not on a mere process start: if the periodic job starts it, both would otherwise fetch every feed. Paused
 * subscriptions ("Keep up to date" off) are part of none of these, only of [refreshFeed].
 */
@Singleton
class PodcastRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val repository: PodcastRepository,
    private val dao: PodcastDao,
    private val settings: PodcastSettings,
    private val localPodcasts: LocalPodcasts,
    private val events: RefreshEvents,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastStaleCheck = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true` while a sync triggered by the user is running (for pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(settings.refreshHours, settings.useMobileData) { hours, mobile -> hours to mobile }
                .distinctUntilChanged()
                .collect { (hours, mobile) -> schedule(hours, mobile) }
        }
    }

    /**
     * On opening the app (at most every [STALE_CHECK_INTERVAL_MS]): sync subscriptions whose last sync is longer ago than
     * the configured interval – only over a network the periodic job would also use. Only reports if there is
     * something new. As with syncing everything: if all fail, it was the network, and it doesn't count as a subscription error.
     */
    fun refreshStale() {
        val now = System.currentTimeMillis()
        val last = lastStaleCheck.get()
        if (now - last < STALE_CHECK_INTERVAL_MS || !lastStaleCheck.compareAndSet(last, now)) return
        scope.launch {
            val hours = settings.refreshHours.first()
            if (hours == 0 || !networkAllows(mobile = settings.useMobileData.first())) return@launch
            val stale = now - hours * HOUR_MS
            // Paused ones never count: they would otherwise be stale forever and start a check on every opening.
            if (dao.feeds().none { it.syncsWithOthers && it.lastRefreshAt < stale }) return@launch
            // The episodes of a subscription that has never been synced don't count as new there anyway.
            val fresh = repository.refreshAll { it.lastRefreshAt < stale }.newCount
            if (fresh > 0) events.emit(RefreshEvent.Finished(RefreshKind.PODCASTS, fresh, failed = false))
        }
    }

    /** On request: reports start and end (with the number of new episodes) to the UI. */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.PODCASTS))
                localPodcasts.sync()
                val summary = repository.refreshAll()
                events.emit(RefreshEvent.Finished(RefreshKind.PODCASTS, summary.newCount, failed = !summary.ok))
            } finally {
                _refreshing.value = false
            }
        }
    }

    /**
     * Pauses or resumes "Keep up to date" for [feedId]. Resuming catches up right away (quietly, like the check on opening
     * the app) if the last sync is older than the interval and the network is one the periodic job would also use.
     */
    fun setKeepUpdated(feedId: String, keepUpdated: Boolean) {
        scope.launch {
            repository.setKeepUpdated(feedId, keepUpdated)
            if (!keepUpdated) return@launch
            val hours = settings.refreshHours.first()
            val feed = dao.feed(feedId) ?: return@launch
            if (hours == 0 || feed.lastRefreshAt >= System.currentTimeMillis() - hours * HOUR_MS) return@launch
            if (networkAllows(mobile = settings.useMobileData.first())) repository.refresh(feedId)
        }
    }

    /** On request: syncs only the subscription [feedId] (a podcast from the device syncs its files), paused or not. */
    fun refreshFeed(feedId: String) {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.PODCASTS))
                localPodcasts.sync()
                val result = repository.refresh(feedId)
                events.emit(RefreshEvent.Finished(RefreshKind.PODCASTS, result?.newEpisodeIds?.size ?: 0, failed = result == null))
            } finally {
                _refreshing.value = false
            }
        }
    }

    private suspend fun schedule(hours: Int, mobile: Boolean) {
        val manager = WorkManager.getInstance(context)
        if (hours == 0) {
            manager.cancelUniqueWork(WORK_NAME)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        manager.ensurePeriodicWork(WORK_NAME, PodcastRefreshWorker::class.java, hours.toLong(), TimeUnit.HOURS, constraints) {
            setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
        }
    }

    /**
     * Like the periodic job's constraint: internet, and without [mobile] only an unmetered network. The network
     * must be validated: behind a captive portal (hotel Wi-Fi before login) every feed would return the login page.
     */
    private fun networkAllows(mobile: Boolean): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return mobile || capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private companion object {
        const val WORK_NAME = "podcast-refresh"
        const val HOUR_MS = 60 * 60 * 1000L
        const val STALE_CHECK_INTERVAL_MS = 10 * 60 * 1000L
    }
}

class PodcastRefreshWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val entryPoint: RefreshEntryPoint,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) :
        this(context, params, EntryPointAccessors.fromApplication(context, RefreshEntryPoint::class.java))

    override suspend fun doWork(): Result {
        // Hidden podcasts (General → Sections): no fetches, no downloads, no notifications.
        if (SpokenMode.PODCASTS !in entryPoint.spokenSettings().areas.first()) return Result.success()
        val summary = entryPoint.repository().refreshAll()
        // Podcasts for which the user doesn't want notifications are missing here.
        val content = NewContent.Podcasts(summary.newEpisodes.filter { it.notify }, summary.moved)
        if (!content.isEmpty) entryPoint.notifier().show(content)
        // Only retry if nothing worked at all (network gone). Otherwise a single dead feed would reload all subscriptions
        // every few minutes; it gets its turn again with the next regular run.
        return if (summary.allFailed && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun repository(): PodcastRepository
        fun notifier(): NewContentNotifier
        fun spokenSettings(): SpokenSettings
    }
}
