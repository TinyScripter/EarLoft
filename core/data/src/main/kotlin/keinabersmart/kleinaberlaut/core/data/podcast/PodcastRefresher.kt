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
import keinabersmart.kleinaberlaut.core.data.refresh.NewContent
import keinabersmart.kleinaberlaut.core.data.refresh.NewContentNotifier
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvent
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvents
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshKind
import keinabersmart.kleinaberlaut.core.data.spoken.LocalPodcasts
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val localPodcasts: LocalPodcasts,
    private val events: RefreshEvents,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val _refreshing = MutableStateFlow(false)

    /** `true`, solange ein Abgleich läuft, den der Nutzer angestoßen hat (für Pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

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
            // Die Folgen eines Abos, das noch nie abgeglichen wurde, sind nicht neu.
            val fresh = dao.feeds().filter { it.lastRefreshAt < stale }.sumOf { feed ->
                val added = repository.refresh(feed.feedId)?.newEpisodeIds?.size ?: 0
                if (feed.lastRefreshAt > 0) added else 0
            }
            if (fresh > 0) events.emit(RefreshEvent.Finished(RefreshKind.PODCASTS, fresh, failed = false))
        }
    }

    /** Auf Anforderung: meldet Start und Ende (mit Zahl der neuen Folgen) an die Oberfläche. */
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

    /** Auf Anforderung: gleicht nur das Abo [feedId] ab (ein Podcast vom Gerät gleicht seine Dateien ab). */
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
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
        val summary = entryPoint.repository().refreshAll()
        // Podcasts, für die der Nutzer keine Hinweise will, fehlen hier.
        val content = NewContent(episodes = summary.newEpisodes.filter { it.notify })
        if (!content.isEmpty) entryPoint.notifier().show(content)
        return if (summary.ok) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun repository(): PodcastRepository
        fun notifier(): NewContentNotifier
    }
}
