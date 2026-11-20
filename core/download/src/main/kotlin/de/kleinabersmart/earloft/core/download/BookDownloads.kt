package de.kleinabersmart.earloft.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.podcast.HeardDownload
import de.kleinabersmart.earloft.core.data.podcast.expiredHeardDownloads
import de.kleinabersmart.earloft.core.data.spoken.BookRepository
import de.kleinabersmart.earloft.core.data.spoken.SpokenSettings
import de.kleinabersmart.earloft.core.data.spoken.automaticBookDownloads
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.FinishedDownloadRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mobile data only if the user allowed it and manually enqueued files are waiting ([manualWaiting]); the automation always
 * downloads on Wi-Fi only. A job with [NetworkType.CONNECTED] therefore only downloads the manually enqueued ones and hands the
 * rest to a Wi-Fi job ([BookDownloadWorker]).
 */
internal fun bookDownloadNetwork(mobileAllowed: Boolean, manualWaiting: Boolean): NetworkType =
    if (mobileAllowed && manualWaiting) NetworkType.CONNECTED else NetworkType.UNMETERED

/**
 * Audiobook downloads: manual, and with the switches under Books → Downloads also automatic (the first ones from
 * "Continue listening", the next volume of a series, soon-expiring books from ARD Sounds; Wi-Fi only, [automaticBookDownloads]). Finished books lose their
 * files after the configured period ([SpokenSettings.bookDeleteFinishedDays], default never), otherwise they stay until the
 * user deletes them. During playback the files replace the stream (`BookPlaybackSource`). Network: Wi-Fi; with the books' own
 * switch "Also download over mobile data" ([SpokenSettings.bookMobileData]) any network for the manually enqueued
 * files; those of the automation (`autoDownload`) always download on Wi-Fi only.
 */
@Singleton
class BookDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: BookDao,
    private val books: BookRepository,
    private val settings: SpokenSettings,
    private val storage: BookStorage,
) {
    private val started = AtomicBoolean(false)

    /** The book in the player (from [start]); its files are kept when cleaning up finished books. */
    private var loadedBookId: Flow<Long?> = flowOf(null)

    /** The automation's last fully enqueued selection; only a different one gets enqueued again. */
    private var handled: List<Long> = emptyList()

    /**
     * Books whose file list the server didn't deliver, with the time of the next attempt (review G36): without it every
     * saved position (every 15 s while listening) asked an unreachable server again. In memory only.
     */
    private val detailsRetryAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /**
     * Enqueues all files of the book that aren't downloaded yet. The file list comes from the server, which must
     * be reachable for this; without it the result is [DownloadStart.OFFLINE].
     */
    suspend fun download(bookId: Long): DownloadStart {
        val mobileAllowed = settings.bookMobileData.first()
        if (books.timeline(bookId) == null) return DownloadStart.OFFLINE
        if (dao.queueBookDownload(bookId) > 0) enqueueWorker(WorkManager.getInstance(context).manualDownloadPolicy(WORK_NAME))
        return connectionState(mobileAllowed)
    }

    private fun connectionState(mobileAllowed: Boolean): DownloadStart {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager?.getNetworkCapabilities(manager.activeNetwork)
        return when {
            capabilities == null || !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) -> DownloadStart.OFFLINE
            !mobileAllowed && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> DownloadStart.WAITING_FOR_WIFI
            else -> DownloadStart.STARTED
        }
    }

    /** Deletes all files of the book (including a waiting or running download). */
    suspend fun remove(bookId: Long) {
        dao.markBookDownloadRemoved(bookId)
        storage.delete(bookId)
    }

    /** Like [EpisodeDownloads.deleteOrphans]: files of deleted books, only without running or waiting downloads. */
    suspend fun deleteOrphans(): Int {
        if (dao.countPendingBookDownloads() > 0) return 0
        return storage.deleteOrphans(dao.downloadedBookTracks().mapNotNull { it.filePath }) + storage.deleteParts()
    }

    /** Deletes the files of finished books whose period has expired (daily from the `HeardCleanupWorker`). @return count. */
    suspend fun removeExpiredFinished(): Int =
        removeExpired(dao.observeFinishedBookDownloads().first(), settings.bookDeleteFinishedDays.first(), loadedBookId.first())

    private suspend fun removeExpired(rows: List<FinishedDownloadRow>, days: Int, loaded: Long?): Int {
        // As with episodes, what counts is when the files were finished: whoever re-downloads a finished book keeps it.
        val finished = rows.filter { it.bookId != loaded }.groupBy { it.bookId }.map { (bookId, files) ->
            HeardDownload(bookId, files.first().finishedAt, files.maxOf { row -> row.filePath?.let(::File)?.lastModified() ?: 0L })
        }
        return expiredHeardDownloads(finished, days, System.currentTimeMillis()).onEach { remove(it) }.size
    }

    /**
     * Once at app start: resume waiting downloads after a process death, apply the network setting,
     * attach the automation and cleanup. [loadedBookId]: what is in the player.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, loadedBookId: Flow<Long?> = flowOf(null)) {
        if (!started.compareAndSet(false, true)) return
        this.loadedBookId = loadedBookId
        scope.launch {
            // After a process death entries may still be RUNNING/QUEUED. KEEP: if the worker itself started the process,
            // a second one would otherwise attach to it and then find nothing.
            if (dao.countPendingBookDownloads() > 0) enqueueWorker(ExistingWorkPolicy.KEEP)
        }
        // The "mobile data" setting applies to waiting downloads immediately: replace the job with new constraints.
        scope.launch {
            settings.bookMobileData.distinctUntilChanged().drop(1).collect {
                if (dao.countPendingBookDownloads() > 0) enqueueWorker(ExistingWorkPolicy.REPLACE)
            }
        }
        // Automation, only with one of its switches on: on every change of progress, books and Wi-Fi, at most every
        // [AUTOMATIC_SAMPLE_MS] (while listening the progress changes every few seconds). Without Wi-Fi nothing is
        // enqueued, it catches up on Wi-Fi; a book without a file list (server unreachable) next time.
        scope.launch {
            combine(settings.bookAutoStarted, settings.bookAutoNextInSeries, settings.bookLoadExpiring, ::Triple)
                .distinctUntilChanged()
                .flatMapLatest { (started, next, expiring) ->
                    if (!started && !next && !expiring) return@flatMapLatest flowOf(emptyList())
                    // Expiring books don't change any row when their period drops below the threshold: the clock triggers it.
                    // The selection only after [sample]: computed on every saved position, most results were discarded (review G39).
                    combine(books.continueListening(), books.books(), unmeteredNetwork(), expiryCheck()) { continueListening, all, wifi, _ ->
                        Triple(continueListening, all, wifi)
                    }
                        .sample(AUTOMATIC_SAMPLE_MS)
                        .map { (continueListening, all, wifi) ->
                            if (!wifi) return@map emptyList()
                            automaticBookDownloads(continueListening, all, started, next, System.currentTimeMillis(), expiring)
                        }
                }
                .collect { bookIds ->
                    if (bookIds != handled && queueAutomatic(bookIds)) handled = bookIds
                }
        }
        scope.launch {
            // Distinct: the list changes on every saved position, its content only when a book is finished (review G39).
            combine(dao.observeFinishedBookDownloads().distinctUntilChanged(), settings.bookDeleteFinishedDays, loadedBookId, ::Triple)
                .distinctUntilChanged()
                .collect { (finished, days, loaded) -> removeExpired(finished, days, loaded) }
        }
    }

    /** Emits immediately and then every [EXPIRY_CHECK_MS] (only while the automation is running). */
    private fun expiryCheck(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(EXPIRY_CHECK_MS)
        }
    }

    /** Whether the default network is unmetered (Wi-Fi); reports every change. */
    private fun unmeteredNetwork(): Flow<Boolean> = callbackFlow {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: run {
            trySend(false)
            close()
            return@callbackFlow
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            }

            override fun onLost(network: Network) {
                trySend(false)
            }
        }
        trySend(connectionState(mobileAllowed = false) == DownloadStart.STARTED)
        manager.registerDefaultNetworkCallback(callback)
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** @return whether all books are done (otherwise one was missing its file list from the server). */
    private suspend fun queueAutomatic(bookIds: List<Long>): Boolean {
        var queued = 0
        var complete = true
        val now = System.currentTimeMillis()
        for (bookId in bookIds) {
            // File list from the server, if not loaded yet; without a connection a while later.
            if ((detailsRetryAt[bookId] ?: 0L) > now) {
                complete = false
                continue
            }
            if (books.timeline(bookId) == null) {
                detailsRetryAt[bookId] = now + DETAILS_RETRY_MS
                complete = false
                continue
            }
            detailsRetryAt.remove(bookId)
            queued += dao.queueBookDownloadAutomatic(bookId)
        }
        if (queued > 0) {
            Log.i(TAG, "Automatic: queued $queued files of ${bookIds.size} books")
            enqueueWorker()
        }
        return complete
    }

    /**
     * The mobile data job has downloaded all manually enqueued files; the automation's files are then downloaded by a
     * Wi-Fi job (appended, it starts when the running one is done).
     */
    internal suspend fun continueOnWifi() = enqueueWorker()

    /**
     * The network depends on what is waiting ([bookDownloadNetwork]): this keeps the automation's files on Wi-Fi, whether
     * the job comes from it, from a manual action, from the mobile data switch or from app start.
     */
    private suspend fun enqueueWorker(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val network = bookDownloadNetwork(settings.bookMobileData.first(), dao.countPendingManualBookDownloads() > 0)
        val mobile = network == NetworkType.CONNECTED
        // An appended job waits for the previous one: otherwise a Wi-Fi job would hold up a manually started mobile one
        // as soon as Wi-Fi is gone. It is replaced even in the middle of a file, which continues there on the next run.
        val chain = if (mobile && policy == ExistingWorkPolicy.APPEND_OR_REPLACE && !mobileWorkerRunning()) {
            ExistingWorkPolicy.REPLACE
        } else {
            policy
        }
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(network)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setInputData(workDataOf(BookDownloadWorker.KEY_MANUAL_ONLY to mobile))
            .apply { if (mobile) addTag(TAG_MOBILE) }
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, chain, request)
    }

    private suspend fun mobileWorkerRunning(): Boolean = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(WORK_NAME).get()
            .any { it.state == WorkInfo.State.RUNNING && TAG_MOBILE in it.tags }
    }

    private companion object {
        const val WORK_NAME = "book-downloads"
        const val TAG_MOBILE = "book-downloads-mobile"
        const val TAG = "BookDownloads"
        const val AUTOMATIC_SAMPLE_MS = 5_000L
        const val EXPIRY_CHECK_MS = 6 * 60 * 60 * 1000L
        const val DETAILS_RETRY_MS = 10 * 60 * 1000L
    }
}
