package de.kleinabersmart.earloft.core.data.spoken

import de.kleinabersmart.earloft.core.model.SpokenMode
import kotlinx.coroutines.flow.first
import android.content.Context
import android.util.Log
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
import de.kleinabersmart.earloft.core.data.refresh.NewContent
import de.kleinabersmart.earloft.core.data.refresh.NewContentNotifier
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvent
import de.kleinabersmart.earloft.core.data.refresh.RefreshEvents
import de.kleinabersmart.earloft.core.data.refresh.RefreshKind
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.work.ensurePeriodicWork
import de.kleinabersmart.earloft.core.database.dao.BookDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps audiobooks (and the podcast files from the device) up to date: when opening the app (at most every 10 min) the files from
 * the device and per server a full sync (> 6 h) or the account's progress, in the background via [BookRefreshWorker]
 * (Wi-Fi only), everything on request. Merely starting the process syncs nothing: it also happens for background jobs
 * and messages from the watch.
 */
@Singleton
class SpokenRefresher @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val events: RefreshEvents,
    private val syncer: BookSyncer,
    private val progressSync: BookProgressSync,
    private val bookmarks: Bookmarks,
    private val servers: AudiobookshelfServers,
    private val dao: BookDao,
    private val localBooks: LocalBooks,
    private val localPodcasts: LocalPodcasts,
    private val ardBooks: ArdBooks,
    private val settings: SpokenSettings,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val started = AtomicBoolean(false)
    private val lastQuick = AtomicLong(0)
    private val lastArd = AtomicLong(0)
    private val _refreshing = MutableStateFlow(false)

    /** `true` while a sync triggered by the user is running (for pull-to-refresh). */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // Books from the device: every time the switch changes (off = remove). The state at startup is synced by
        // [refreshQuick] as soon as the app becomes visible.
        scope.launch {
            settings.useLocalBooks.distinctUntilChanged().drop(1).collect { enabled -> if (enabled) localBooks.sync() else localBooks.remove() }
        }
        scope.launch {
            settings.useLocalPodcasts.distinctUntilChanged().drop(1).collect { enabled -> if (enabled) localPodcasts.sync() else localPodcasts.remove() }
        }
        scope.launch { schedule() }
    }

    /** Keeps the library up to date even without the app open (Wi-Fi only), so new books can be announced. */
    private suspend fun schedule() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()
        // Changed constraints also apply to an already scheduled job (the interval stays); identical ones leave it untouched.
        WorkManager.getInstance(context).ensurePeriodicWork(
            WORK_NAME, BookRefreshWorker::class.java, BACKGROUND_INTERVAL_HOURS, TimeUnit.HOURS, constraints,
        ) {
            setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 5, TimeUnit.MINUTES)
        }
    }

    /**
     * When opening the app: send pending progress, then per server a full sync if the last one was more than
     * [FULL_SYNC_INTERVAL_MS] ago, otherwise only adopt the progress of other devices; the files from the device alongside.
     * Without [books] or [podcasts] (section hidden) the respective part is skipped.
     */
    fun refreshQuick(books: Boolean = true, podcasts: Boolean = true) {
        if (!books && !podcasts) return
        val now = System.currentTimeMillis()
        val last = lastQuick.get()
        if (now - last < QUICK_INTERVAL_MS || !lastQuick.compareAndSet(last, now)) return
        scope.launch {
            // New files in "Audiobooks" alongside: with folder access, the folder queries the provider once per book folder; the
            // servers don't wait for it (like `BookSyncer.syncAll`).
            alongside({ if (books) localBooks.sync() }, { if (podcasts) localPodcasts.sync() }) { syncServersQuick(books, now) }
        }
    }

    private suspend fun syncServersQuick(books: Boolean, now: Long) {
        // Hidden books (General → Sections): no contact with Audiobookshelf.
        if (!books) return
        progressSync.pushPending()
        bookmarks.pushPending()
        var fresh = 0
        servers.all().forEach { server ->
            if (now - (dao.server(server.serverId)?.lastFullSyncAt ?: 0) > FULL_SYNC_INTERVAL_MS) {
                // The full sync also adopts the account's progress.
                fresh += syncer.syncSafely(server).newBooks.size
                return@forEach
            }
            try {
                bookmarks.pull(server.serverId) { progressSync.pullAll(server).bookmarks }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "Fortschritt von ${server.baseUrl} nicht abrufbar", e)
            }
        }
        // The books from ARD Sounds don't belong to a server: they are synced even without Audiobookshelf.
        val lastArdAt = lastArd.get()
        if (now - lastArdAt > FULL_SYNC_INTERVAL_MS && lastArd.compareAndSet(lastArdAt, now)) fresh += ardBooks.refresh().newBooks.size
        if (fresh > 0) events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, fresh, failed = false))
    }

    /** On request: reports start and end (with the number of new books) to the UI. */
    fun refreshAll() {
        scope.launch {
            _refreshing.value = true
            try {
                events.emit(RefreshEvent.Started(RefreshKind.BOOKS))
                val result = syncer.syncAll()
                events.emit(RefreshEvent.Finished(RefreshKind.BOOKS, result.newBooks.size, failed = !result.ok))
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

/**
 * Runs [main] while [side] runs alongside, and ends when both are done. That way [main] doesn't wait for the slower part
 * alongside (when opening the app: the servers don't wait for the files from the device, review F23).
 */
internal suspend fun alongside(vararg side: suspend () -> Unit, main: suspend () -> Unit) = coroutineScope {
    side.forEach { launch { it() } }
    main()
}

class BookRefreshWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RefreshEntryPoint::class.java)
        // Hidden books (General → Sections): no sync with Audiobookshelf.
        if (SpokenMode.BOOKS !in entryPoint.spokenSettings().areas.first()) return Result.success()
        val servers = entryPoint.bookServers().all()
        val ardBooks = entryPoint.ardBooks()
        val ardOn = ardBooks.enabled.first()
        if (servers.isEmpty() && !ardOn) return Result.success()
        val results = servers.map { entryPoint.bookSyncer().syncSafely(it) }
        // Even without a server: books from ARD Sounds (expiry, new books of subscribed shows).
        val ard = ardBooks.refresh()
        val fresh = results.flatMap { it.newBooks } + ard.newBooks
        if (fresh.isNotEmpty()) entryPoint.notifier().show(NewContent.Books(fresh))
        // As with podcasts: a server that isn't reachable here (home network only) would otherwise re-sync all servers every
        // few minutes; it retries only if none succeeded.
        val reached = results.any { it.ok } || (servers.isEmpty() && ard.ok)
        return if (!reached && runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    private companion object {
        const val MAX_RETRIES = 3
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface RefreshEntryPoint {
        fun bookSyncer(): BookSyncer
        fun bookServers(): AudiobookshelfServers
        fun ardBooks(): ArdBooks
        fun notifier(): NewContentNotifier
        fun spokenSettings(): SpokenSettings
    }
}
