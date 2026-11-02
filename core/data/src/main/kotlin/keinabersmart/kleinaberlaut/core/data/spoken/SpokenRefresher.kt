package keinabersmart.kleinaberlaut.core.data.spoken

import android.content.Context
import android.util.Log
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
import keinabersmart.kleinaberlaut.core.data.refresh.NewContent
import keinabersmart.kleinaberlaut.core.data.refresh.NewContentNotifier
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvent
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshEvents
import keinabersmart.kleinaberlaut.core.data.refresh.RefreshKind
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hält Hörbücher (und die Podcast-Dateien vom Gerät) aktuell: Vollabgleich beim Start (> 6 h), beim Öffnen der App der Fortschritt des Kontos
 * (höchstens alle 10 min), auf Anforderung alles.
 */
@Singleton
class SpokenRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val events: RefreshEvents,
    private val syncer: BookSyncer,
    private val progressSync: BookProgressSync,
    private val servers: AudiobookshelfServers,
    private val dao: BookDao,
    private val localBooks: LocalBooks,
    private val localPodcasts: LocalPodcasts,
    private val settings: SpokenSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuick = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true`, solange ein Abgleich läuft, den der Nutzer angestoßen hat (für Pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Bücher vom Gerät: beim Start abgleichen und jedes Mal, wenn der Schalter wechselt (aus = entfernen).
        scope.launch {
            settings.useLocalBooks.distinctUntilChanged().collect { enabled -> if (enabled) localBooks.sync() else localBooks.remove() }
        }
        scope.launch {
            settings.useLocalPodcasts.distinctUntilChanged().collect { enabled -> if (enabled) localPodcasts.sync() else localPodcasts.remove() }
        }
        schedule()
        scope.launch {
            val now = System.currentTimeMillis()
            progressSync.pushPending()
            val fresh = servers.all()
                .filter { now - (dao.server(it.serverId)?.lastFullSyncAt ?: 0) > FULL_SYNC_INTERVAL_MS }
                .sumOf { syncer.syncSafely(it).newBooks }
            if (fresh > 0) events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, fresh, failed = false))
        }
    }

    /** Hält die Bibliothek auch ohne geöffnete App aktuell (nur im WLAN), damit neue Bücher gemeldet werden können. */
    private fun schedule() {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()
        val request = PeriodicWorkRequestBuilder<BookRefreshWorker>(BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS)
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /** Beim Öffnen der App: Fortschritt, den andere Geräte geschrieben haben, übernehmen. */
    fun refreshQuick() {
        val now = System.currentTimeMillis()
        val last = lastQuick.get()
        if (now - last < QUICK_INTERVAL_MS || !lastQuick.compareAndSet(last, now)) return
        scope.launch {
            // Neue Dateien in „Audiobooks“; unveränderte Bücher kosten nur die MediaStore-Abfrage.
            localBooks.sync()
            localPodcasts.sync()
            progressSync.pushPending()
            servers.all().forEach { server ->
                try {
                    progressSync.pullAll(server)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Log.w(TAG, "Fortschritt von ${server.baseUrl} nicht abrufbar", e)
                }
            }
        }
    }

    /** Auf Anforderung: meldet Start und Ende (mit Zahl der neuen Bücher) an die Oberfläche. */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.BOOKS))
                val result = syncer.syncAll()
                events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, result.newBooks, failed = !result.ok))
            } finally {
                _refreshing.value = false
            }
        }
    }

    private companion object {
        const val TAG = "SpokenRefresher"
        const val WORK_NAME = "book-refresh"
        const val BACKGROUND_INTERVAL_HOURS = 12L
        const val FULL_SYNC_INTERVAL_MS = 6 * 60 * 60 * 1000L
        const val QUICK_INTERVAL_MS = 10 * 60 * 1000L
    }
}

class BookRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
        val servers = entryPoint.bookServers().all()
        if (servers.isEmpty()) return Result.success()
        val results = servers.map { entryPoint.bookSyncer().syncSafely(it) }
        val fresh = results.sumOf { it.newBooks }
        if (fresh > 0) entryPoint.notifier().show(NewContent(books = fresh))
        return if (results.all { it.ok }) Result.success() else Result.retry()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun bookSyncer(): BookSyncer
        fun bookServers(): AudiobookshelfServers
        fun notifier(): NewContentNotifier
    }
}
