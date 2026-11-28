package de.kleinabersmart.earloft.wear.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Network
import android.os.BatteryManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.wear.ongoing.OngoingActivity
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadProfile
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.download.ProgressThrottle
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearDownloadError
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.wear.EXTRA_OPEN_STORAGE
import de.kleinabersmart.earloft.wear.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Downloads the queued songs of the selections from the server (Wear concept 8.2): only over an explicitly
 * requested Wi-Fi, depending on the setting only on the charger (constraint of the job, see [WearDownloadScheduler]).
 * Without Wi-Fi a new run follows later with a growing interval ([retryLater]), after [MAX_WIFI_ATTEMPTS] attempts only on the charger. Songs only the phone has, and songs for
 * which the server failed three times, come via the phone ([PhoneFileFetcher], concept 8.3/8.4).
 * After the music, the podcast episodes (16.1): directly from the feed over the same Wi-Fi, episodes from the phone's device and after
 * three failed attempts via the phone. Then the audiobooks (16.2): directly from Audiobookshelf over the same Wi-Fi (an
 * interrupted file resumes on the next run), books from the phone's device and after three failed attempts via the phone.
 */
class WearDownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    private val entryPoint: WearDownloadEntryPoint,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) :
        this(context, params, EntryPointAccessors.fromApplication(context, WearDownloadEntryPoint::class.java))

    private val downloadDao = entryPoint.downloadDao()
    private val wearDao = entryPoint.wearDao()
    private val storage = entryPoint.storage()
    private val state = entryPoint.state()
    private val coverFiles = entryPoint.coverFiles()
    private val edges = entryPoint.edges()
    private val podcastDao = entryPoint.podcastDao()
    private val bookDao = entryPoint.bookDao()

    /**
     * The Wi-Fi request for downloads from the servers; held only while one is running. Every transfer via the phone releases it
     * first: those go over the Bluetooth channel at about 100 KB/s, and a 300 MB file kept the Wi-Fi on for some 50 min
     * (review G7, `wear_konzept.md` 8.4). The next server download requests it again.
     */
    private var lease: WifiLease? = null

    private suspend fun wifiNetwork(): Network? =
        (lease ?: edges.acquireWifi()?.also { lease = it })?.network

    private fun releaseWifi() {
        lease?.close()
        lease = null
    }

    /**
     * The Wi-Fi went away during the download (review G30): that is no server failure. Otherwise three such runs sent the rest
     * of the selection as originals over Bluetooth. Releases the request; waiting for Wi-Fi takes over.
     */
    private fun wifiLost(): Boolean {
        if (lease?.lost != true) return false
        Log.i(TAG, "Wi-Fi lost during the download, waiting for Wi-Fi instead of counting a server failure")
        releaseWifi()
        return true
    }

    /**
     * Songs left out in this run because their original from the phone doesn't fit (review G11): the planner counted them
     * as Opus from the server, the phone only has the original (a FLAC is many times larger).
     */
    private val waitingForServer = mutableListOf<String>()

    /**
     * The storage ran full during a transfer ([storageFull]): the run ends instead of starting the next transfers, which would
     * fail the same way. The entry is back to "not queued"; `WatchStatusPublisher` has the planner queue it again once there
     * is room.
     */
    private var stoppedForStorage = false

    /**
     * Never fails: a new selection is appended to a running job (review4 T63), and a failed job would take it down with it, so it
     * waited for the next sync (8.6 Y29). Anything unexpected is retried like a missing server instead.
     */
    override suspend fun doWork(): Result = try {
        download()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.e(TAG, "Download run failed, retrying later", e)
        // It waits like after a server error, not for Wi-Fi: the charger may replace the job only in the latter case (review5 P65).
        state.setWaitingForWifi(false)
        retryLater()
    }

    private suspend fun download(): Result {
        downloadDao.requeueRunning()
        podcastDao.requeueRunningDownloads()
        bookDao.requeueRunningBookDownloads()
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload()
            .any { !coverFiles.has(it) && !state.coverRecentlyMissing(WatchState.albumCoverKey(it)) }
        val pending = pendingCount()
        if (pending == 0 && !coversMissing) return finish()
        if (!hasRoomToStart()) return finish()
        // No foreground service with download icon just for missing covers: a cover that never exists would otherwise trigger it
        // on every run (Review V2).
        if (pending > 0) startForeground()

        // Songs only the phone has need no Wi-Fi. If the phone is unreachable, only they wait; whatever the server
        // delivers is downloaded anyway (Review V1).
        val phoneReachable = drainPhoneQueue()
        if (stoppedForStorage) return finish()

        try {
            val profile = state.settings.value.quality.toProfile()
            while (true) {
                if (stoppedForStorage) return finish()
                val next = downloadDao.nextQueuedFromServers(waitingForServer) ?: break
                val uid = next.sourceUid
                if (state.hasServerFailedTooOften(uid)) {
                    // The server has already failed several times (switch elsewhere, retried later).
                    if (!viaPhone(next, serverFailed = true)) return waitForPhone()
                    continue
                }
                if (!edges.knowsSource(uid)) {
                    // The watch does not know the server (no credentials yet): go via the phone right away, without Wi-Fi (Review V4).
                    Log.i(TAG, "Source of $uid unknown here, via the phone")
                    if (!viaPhoneForUnknownSource(next)) return waitForPhone()
                    continue
                }
                val network = wifiNetwork() ?: return waitForWifi()
                state.setWaitingForWifi(false)
                val throttle = ProgressThrottle()
                val outcome = edges.downloadSong(next, profile, network) { done, total ->
                    val percent = throttle.next(done, total)
                    if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
                }
                when (outcome) {
                    is DownloadOutcome.NoSpace -> {
                        // Not failed and not via the phone (it would fail there too, review H4): without its entry the planner
                        // queues the song again once there is room.
                        downloadDao.delete(listOf(uid))
                        storageFull()
                    }
                    is DownloadOutcome.Retry -> {
                        if (wifiLost()) {
                            downloadDao.requeue(uid)
                            return waitForWifi()
                        }
                        val failures = state.recordServerFailure(uid)
                        // An unreachable server affects all waiting songs from it: otherwise each would need three runs of its
                        // own, and WorkManager's backoff would keep growing meanwhile.
                        val sourceId = downloadDao.getSongSource(uid)?.sourceId
                        if (sourceId != null) {
                            state.recordServerFailures(downloadDao.queuedUidsOfSource(sourceId).filter { it != uid })
                            // New password or token on the phone: the answer replaces the credentials before the next attempt.
                            if (outcome.isAuthRejection) edges.requestCredentials(listOf(sourceId))
                        }
                        // The text is logged explicitly: Log.w with a Throwable prints no stack trace for UnknownHostException.
                        Log.w(TAG, "Download $uid interrupted (failed attempt $failures of ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        if (failures < WatchState.SERVER_ATTEMPTS) {
                            downloadDao.requeue(uid)
                            return retryLater()
                        }
                        if (!viaPhone(next, serverFailed = true)) return waitForPhone()
                    }
                    is DownloadOutcome.Failed -> {
                        Log.w(TAG, "Download $uid failed: ${outcome.reason}")
                        // File gone, login refused or similar: maybe the phone has it.
                        if (outcome.sourceUnavailable) {
                            if (!viaPhoneForUnknownSource(next)) return waitForPhone()
                        } else {
                            state.markViaPhone(listOf(uid))
                            // The reason is in the log; the display gets no internal text, but what happens now. The
                            // server was reachable but did not deliver the file (Review V16).
                            state.setLastError(WearDownloadError.SERVER_REFUSED_VIA_PHONE)
                            if (!viaPhone(next, serverFailed = true)) return waitForPhone()
                        }
                    }
                    DownloadOutcome.Done -> {
                        state.clearServerFailures(uid)
                        state.clearRetries()
                        state.setLastError(null)
                    }
                    DownloadOutcome.Cancelled -> Unit
                }
            }
            while (true) {
                if (stoppedForStorage) return finish()
                val track = podcastDao.nextQueuedDownload() ?: break
                val key = episodeFailureKey(track.bookId)
                if (track.isPhoneOnly || state.hasServerFailedTooOften(key)) {
                    if (!episodeViaPhone(track)) return waitForPhone()
                    continue
                }
                val network = wifiNetwork() ?: return waitForWifi()
                state.setWaitingForWifi(false)
                val outcome = downloadSpoken(track) { onProgress -> edges.downloadEpisode(track, network, onProgress) }
                when (outcome) {
                    is DownloadOutcome.NoSpace -> spokenDidNotFit(track, outcome)
                    is DownloadOutcome.Retry -> {
                        if (wifiLost()) {
                            podcastDao.requeueDownload(track.bookId)
                            return waitForWifi()
                        }
                        val failures = state.recordServerFailure(key)
                        Log.w(TAG, "Episode ${track.bookId} interrupted (failed attempt $failures of ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        if (failures < WatchState.SERVER_ATTEMPTS) {
                            podcastDao.requeueDownload(track.bookId)
                            return retryLater()
                        }
                        if (!episodeViaPhone(track)) return waitForPhone()
                    }
                    is DownloadOutcome.Failed -> {
                        // Address gone, no audio file or similar: maybe the phone has downloaded it.
                        Log.w(TAG, "Episode ${track.bookId} failed: ${outcome.reason}")
                        if (!episodeViaPhone(track)) return waitForPhone()
                    }
                    DownloadOutcome.Done -> {
                        state.clearServerFailures(key)
                        // Something got ahead: the next retry starts from the short interval again (review2 J6).
                        state.clearRetries()
                        state.clearFileSize(track.bookId, 0)
                        // Like for songs: a download that worked ends the last error (it stayed after episodes and books).
                        state.setLastError(null)
                    }
                    DownloadOutcome.Cancelled -> Unit
                }
            }
            if (bookDao.nextQueuedBookTrack() != null) renewExpiringBookKeys()
            while (true) {
                if (stoppedForStorage) return finish()
                val track = bookDao.nextQueuedBookTrack() ?: break
                val key = bookFailureKey(track.bookId)
                if (track.isPhoneOnlyBook || state.hasServerFailedTooOften(key)) {
                    if (!bookViaPhone(track)) return waitForPhone()
                    continue
                }
                val book = bookDao.book(track.bookId) ?: break
                // Books from ARD Sounds are downloaded by the watch directly from their address like episodes, without a key.
                if (!isArdSoundsBook(book.serverId) && !edges.hasBookServer(book.serverId)) {
                    // The key is still on its way (request to the phone running) or missing there; counts as a failed attempt.
                    val failures = state.recordServerFailure(key)
                    Log.w(TAG, "Book ${track.bookId}: Audiobookshelf without key (failed attempt $failures of ${WatchState.SERVER_ATTEMPTS})")
                    if (failures < WatchState.SERVER_ATTEMPTS) return retryLater()
                    if (!bookViaPhone(track)) return waitForPhone()
                    continue
                }
                val network = wifiNetwork() ?: return waitForWifi()
                state.setWaitingForWifi(false)
                val outcome = downloadSpoken(track) { onProgress -> edges.downloadBookTrack(track, network, onProgress) }
                when (outcome) {
                    is DownloadOutcome.NoSpace -> spokenDidNotFit(track, outcome)
                    is DownloadOutcome.Retry -> {
                        if (wifiLost()) {
                            bookDao.requeueBookTrack(track.bookId, track.trackIndex)
                            return waitForWifi()
                        }
                        val failures = state.recordServerFailure(key)
                        Log.w(TAG, "Book ${track.bookId} file ${track.trackIndex} interrupted (failed attempt $failures of ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        // The partial file stays; the next run resumes there.
                        bookDao.requeueBookTrack(track.bookId, track.trackIndex)
                        // New key on the phone: the answer replaces it before the next attempt.
                        if (outcome.isAuthRejection) edges.requestCredentials(listOf(book.serverId))
                        if (failures < WatchState.SERVER_ATTEMPTS) return retryLater()
                        if (!bookViaPhone(track)) return waitForPhone()
                    }
                    is DownloadOutcome.Failed -> {
                        // File gone, key refused or similar: maybe the phone has downloaded the book.
                        Log.w(TAG, "Book ${track.bookId} failed: ${outcome.reason}")
                        if (!bookViaPhone(track)) return waitForPhone()
                    }
                    DownloadOutcome.Done -> {
                        state.clearServerFailures(key)
                        // Something got ahead: the next retry starts from the short interval again (review2 J6).
                        state.clearRetries()
                        state.clearFileSize(track.bookId, track.trackIndex)
                        state.setLastError(null)
                    }
                    DownloadOutcome.Cancelled -> Unit
                }
            }
            // While the Wi-Fi of the downloads is still held: over the Bluetooth proxy the home server is often unreachable, and
            // a missing cover is only asked for again hours later (review2 J90).
            lease?.network?.let { network -> cacheCoversOver(network) }
        } finally {
            releaseWifi()
        }
        // Unbound: what the Wi-Fi pass did not get (Wi-Fi gone meanwhile, review3 Q30) comes from the phone; what it got is
        // already there and is skipped.
        cacheCovers()
        if (!phoneReachable) return waitForPhone()
        return finish()
    }

    /**
     * Covers from the servers only, with the process bound to [network]: the cover loading doesn't know the network of the
     * downloads. Nothing is noted as missing and the phone isn't asked: if the Wi-Fi goes meanwhile, every server cover
     * fails here without falling back, and [cacheCovers] afterwards does that unbound.
     */
    private suspend fun cacheCoversOver(network: Network) {
        val connectivity = applicationContext.getSystemService(ConnectivityManager::class.java)
        val bound = connectivity.bindProcessToNetwork(network)
        try {
            cacheCovers(serversOnly = true)
        } finally {
            if (bound) connectivity.bindProcessToNetwork(null)
        }
    }

    /**
     * With OpenID login, the access token from the phone is valid for at most one hour (concept 12.11): if it expires soon,
     * request a fresh one beforehand and wait briefly for the answer instead of wasting a failed attempt on a 401. If the
     * phone does not answer, continue with the old one; a refusal then asks again as before.
     */
    private suspend fun renewExpiringBookKeys() {
        val expiring = edges.expiringBookKeys(KEY_MARGIN_SECONDS)
        if (expiring.isEmpty()) return
        Log.i(TAG, "Key for ${expiring.size} Audiobookshelf servers expiring, requesting a fresh one from the phone")
        edges.requestCredentials(expiring)
        withTimeoutOrNull(KEY_WAIT_MS) {
            while (edges.expiringBookKeys(KEY_MARGIN_SECONDS).any { it in expiring }) delay(KEY_POLL_MS)
        }
    }

    /**
     * The phone was unreachable for a download: at most [WatchState.PHONE_ATTEMPTS] runs, then the job waits for the
     * charger; if the phone checks in earlier, `PhoneLibrarySync` reschedules right away (Review V1). Otherwise every run starts the
     * foreground service and transmits over Bluetooth. The watch learns that it checked in via [de.kleinabersmart.earloft.wear.sync.PhoneReturn].
     */
    private suspend fun waitForPhone(): Result {
        val runs = state.recordPhoneUnreachable()
        val step = phoneWaitStep(runs, pluggedIn = isPluggedIn())
        if (step != PhoneWaitStep.RETRY && !state.waitingForPhone) {
            state.waitingForPhone = true
            edges.announcePhoneWait()
        }
        return when (step) {
            PhoneWaitStep.RETRY -> {
                Log.i(TAG, "Phone unreachable, retrying later (run $runs)")
                retryLater()
            }
            PhoneWaitStep.RETRY_ON_CHARGER -> {
                // A job without delay would restart immediately on the charger and fail again after 30 s, without
                // end. This way the interval grows (up to 5 h) until the phone checks in.
                Log.i(TAG, "Phone unreachable after $runs runs, retrying later on the charger or when it checks in")
                retryLater(onCharger = true)
            }
            PhoneWaitStep.WAIT_FOR_CHARGER -> {
                Log.i(TAG, "Phone unreachable after $runs runs, continuing on the charger or when it checks in")
                entryPoint.scheduler().enqueueOnCharger()
                Result.success()
            }
        }
    }

    /** On power (even fully charged): then a job with a charging constraint starts immediately. */
    private fun isPluggedIn(): Boolean =
        applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
            ?.let { it != 0 } == true

    /**
     * No Wi-Fi, also one lost during the run ([wifiLost]), does not count as a failed server attempt: otherwise the whole
     * selection would come over Bluetooth.
     */
    private fun waitForWifi(): Result {
        state.setWaitingForWifi(true)
        val attempt = state.retryRuns + 1
        if (attempt < MAX_WIFI_ATTEMPTS) {
            Log.i(TAG, "No Wi-Fi, retrying later (attempt $attempt)")
            return retryLater()
        }
        // Every attempt starts a foreground service and turns Wi-Fi on for up to 30 s: after a few
        // attempts continue only on the charger.
        if (isPluggedIn()) {
            // Already on the charger: a job without delay would start right away and the cycle would begin again, all night
            // (review G8). Like waiting for the phone, the interval grows instead.
            Log.i(TAG, "No Wi-Fi after $attempt attempts on the charger, retrying later")
            return retryLater(onCharger = true)
        }
        Log.i(TAG, "No Wi-Fi after $attempt attempts, continuing on the charger")
        entryPoint.scheduler().enqueueOnCharger()
        return Result.success()
    }

    /**
     * This run should be retried later: as a new job after [retryDelayMs], not via `Result.retry()`. WorkManager's backoff
     * was lengthened by every stop by the system (Review W1): it counts such stops as attempts and calculates
     * from the last `retry()`. [onCharger]: the new job only on the charger (otherwise like this one).
     */
    private fun retryLater(onCharger: Boolean = this.onCharger): Result {
        val runs = state.recordRetry()
        val delayMs = retryDelayMs(runs)
        Log.i(TAG, "Next run in ${delayMs / 1000} s (retry $runs${if (onCharger) ", on the charger" else ""})")
        entryPoint.scheduler().enqueueRetry(delayMs, onCharger)
        return Result.success()
    }

    /** This job comes from [WearDownloadScheduler.enqueueOnCharger] or a retry of it. */
    private val onCharger: Boolean get() = inputData.getBoolean(KEY_ON_CHARGER, false)

    /**
     * Fetches an episode via the phone. @return `false` if the phone was unreachable; the episode then keeps waiting.
     * If the phone has no file, the episode stays failed (new attempt when the phone adds it to the manifest again).
     */
    private suspend fun episodeViaPhone(track: BookTrackEntity): Boolean {
        releaseWifi()
        return when (val outcome = downloadSpoken(track) { onProgress -> edges.episodeFromPhone(track, onProgress) }) {
            DownloadOutcome.Done -> {
                state.deliveredViaPhone(episodeFailureKey(track.bookId), moreToCome = false)
                state.clearPhoneUnreachable()
                state.setLastError(null)
                true
            }
            is DownloadOutcome.Failed -> {
                state.clearPhoneUnreachable()
                Log.w(TAG, "Episode ${track.bookId} not loaded from the phone: ${outcome.reason}")
                true
            }
            is DownloadOutcome.NoSpace -> {
                state.clearPhoneUnreachable()
                // Like from the server: with the real size the planner only queues it again once that much is free (review2 J7).
                spokenDidNotFit(track, outcome)
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Phone unreachable for episode ${track.bookId} (attempt ${state.retryRuns + 1})", outcome.cause)
                podcastDao.requeueDownload(track.bookId)
                state.setLastError(WearDownloadError.PHONE_UNREACHABLE)
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /** Like [episodeViaPhone] for a book file. */
    private suspend fun bookViaPhone(track: BookTrackEntity): Boolean {
        releaseWifi()
        return when (val outcome = downloadSpoken(track) { onProgress -> edges.bookTrackFromPhone(track, onProgress) }) {
            DownloadOutcome.Done -> {
                state.deliveredViaPhone(bookFailureKey(track.bookId), moreToCome = bookDao.hasWaitingTracks(track.bookId))
                state.clearPhoneUnreachable()
                state.setLastError(null)
                true
            }
            is DownloadOutcome.Failed -> {
                state.clearPhoneUnreachable()
                Log.w(TAG, "Book ${track.bookId} file ${track.trackIndex} not loaded from the phone: ${outcome.reason}")
                true
            }
            is DownloadOutcome.NoSpace -> {
                state.clearPhoneUnreachable()
                // Like from the server: with the real size the planner only queues it again once that much is free (review2 J7).
                spokenDidNotFit(track, outcome)
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Phone unreachable for book ${track.bookId} (attempt ${state.retryRuns + 1})", outcome.cause)
                bookDao.requeueBookTrack(track.bookId, track.trackIndex)
                state.setLastError(WearDownloadError.PHONE_UNREACHABLE)
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /**
     * Downloads a file of an episode or book via [download] and reports its progress to the notification and the
     * UI ([WatchState.spokenFileProgress], "Download" button in the "Phone" section).
     */
    private suspend fun downloadSpoken(
        track: BookTrackEntity,
        download: suspend (onProgress: suspend (Long, Long?) -> Unit) -> DownloadOutcome,
    ): DownloadOutcome {
        val throttle = ProgressThrottle()
        state.setSpokenFileProgress(SpokenFileProgress(track.bookId, track.trackIndex, 0, null))
        try {
            return download { done, total ->
                val percent = throttle.next(done, total)
                if (percent != null) {
                    state.setSpokenFileProgress(SpokenFileProgress(track.bookId, track.trackIndex, done, total))
                    notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
                }
            }
        } finally {
            state.setSpokenFileProgress(null)
        }
    }

    /**
     * An episode or book file did not fit (review H4; over the phone review2 J7): from the server not via the phone, the file would
     * not fit there either.
     * Back to "not queued", its partial file is already gone (H26); with its real size the planner only queues it again once
     * that much room is free.
     */
    private suspend fun spokenDidNotFit(track: BookTrackEntity, outcome: DownloadOutcome.NoSpace) {
        Log.i(TAG, "Book ${track.bookId} file ${track.trackIndex} does not fit (${outcome.totalBytes ?: "?"} bytes)")
        bookDao.unqueueTrack(track.bookId, track.trackIndex)
        outcome.totalBytes?.let { state.recordFileSize(track.bookId, track.trackIndex, it) }
        storageFull()
    }

    private fun storageFull() {
        Log.i(TAG, "Storage full, ending the run; the planner queues the rest again once there is room")
        state.setStorageFullError()
        stoppedForStorage = true
        StorageRoomWorker.schedule(applicationContext)
    }

    /** Whether the original of [uid] (the size the server reported for it) fits into the storage budget. Unknown size: try. */
    private suspend fun originalFits(uid: String): Boolean {
        val size = state.songSize(uid) ?: downloadDao.getSongSource(uid)?.sizeBytes?.takeIf { it > 0 } ?: return true
        val settings = state.settings.value
        val free = withContext(Dispatchers.IO) { storage.freeBytes() }
        // A transfer that broke off resumes; what has arrived is already on the storage.
        val received = withContext(Dispatchers.IO) { storage.phonePartFileFor(uid).length() }
        return size - received <= downloadBudget(settings, free, wearDao.getDoneBytes() + bookDao.trackTotals().doneBytes, reservedBytes = 0)
    }

    private suspend fun pendingCount(): Int =
        downloadDao.countPending() + podcastDao.countPendingDownloads() + bookDao.countPendingBookDownloads()

    /**
     * Checks the free storage before the start (planning and start are separated in time). If not even the
     * smallest waiting track fits under the limit, everything stays queued and the job ends without a failed attempt: the planner
     * queues again after the next sync or on changed settings.
     */
    private suspend fun hasRoomToStart(): Boolean {
        val settings = state.settings.value
        val pending = wearDao.getPendingCandidates()
        val free = withContext(Dispatchers.IO) { storage.freeBytes() }
        val budget = downloadBudget(settings, free, wearDao.getDoneBytes() + bookDao.trackTotals().doneBytes, reservedBytes = 0)
        // Episodes and book files count too: a small episode may fit where no song does (review2 J89).
        val spoken = (podcastDao.pendingTracks() + bookDao.pendingBookTracks())
            .filter { it.downloadState == TrackDownload.QUEUED }
            .map { estimateEpisodeBytes(it.durationMs) }
        if (hasRoomForAny(pending.map { estimateBytes(it, settings.quality, state.songSize(it.uid)) } + spoken, budget)) return true
        // [WatchState.missingForStorage] stays as the planner counted it: these tracks are queued, not left out.
        Log.i(TAG, "Storage full: no waiting track fits (budget $budget bytes), not starting")
        return false
    }

    /**
     * Downloads all waiting songs and episodes that only the phone has. @return `false` if the phone is missing and it
     * should be retried later.
     */
    private suspend fun drainPhoneQueue(): Boolean {
        while (!stoppedForStorage) {
            val next = downloadDao.nextQueuedFromPhone() ?: break
            if (!viaPhone(next, serverFailed = false)) return false
        }
        for (track in podcastDao.pendingTracks().filter { it.isPhoneOnly && it.downloadState == TrackDownload.QUEUED }) {
            if (stoppedForStorage) return true
            if (!episodeViaPhone(track)) return false
        }
        for (track in bookDao.pendingBookTracks().filter { it.isPhoneOnlyBook && it.downloadState == TrackDownload.QUEUED }) {
            if (stoppedForStorage) return true
            if (!bookViaPhone(track)) return false
        }
        return true
    }

    /**
     * The watch does not know the source of [download]: this affects all waiting songs from it, they count toward the remaining time
     * via the phone right away. @return like [viaPhone].
     */
    private suspend fun viaPhoneForUnknownSource(download: DownloadEntity): Boolean {
        releaseWifi()
        val uid = download.sourceUid
        val sourceId = downloadDao.getSongSource(uid)?.sourceId
        state.markViaPhone(listOf(uid) + sourceId?.let { downloadDao.queuedUidsOfSource(it) }.orEmpty())
        state.setLastError(WearDownloadError.SERVER_VIA_PHONE)
        return viaPhone(download, serverFailed = true)
    }

    /**
     * Fetches [download] in its original form from the phone (Wear concept 8.4). [serverFailed]: the server did not manage it
     * (otherwise only the phone has it); this determines the error text if the phone has nothing either.
     * @return `false` if the phone was unreachable; the entry then keeps waiting.
     */
    private suspend fun viaPhone(download: DownloadEntity, serverFailed: Boolean): Boolean {
        releaseWifi()
        val uid = download.sourceUid
        if (serverFailed && !originalFits(uid)) {
            // Stays queued and waits for the server, which delivers it as Opus; its failed attempts start over, so the next run
            // asks the server again instead of the phone.
            Log.i(TAG, "Original of $uid does not fit in the storage, waiting for the server")
            waitingForServer += uid
            state.clearServerFailures(uid)
            downloadDao.requeue(uid)
            return true
        }
        // Back to QUEUED: from RUNNING (network error) or FAILED (server refused).
        downloadDao.requeue(uid)
        downloadDao.retryFailed(listOf(uid))
        val throttle = ProgressThrottle()
        return when (val outcome = edges.songFromPhone(download) { done, total ->
            val percent = throttle.next(done, total)
            if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
        }) {
            DownloadOutcome.Done -> {
                state.deliveredViaPhone(uid, moreToCome = false)
                state.clearSongSize(uid)
                state.clearPhoneUnreachable()
                state.setLastError(null)
                true
            }
            is DownloadOutcome.Failed -> {
                state.clearPhoneUnreachable()
                Log.w(TAG, "$uid not loaded from the phone: ${outcome.reason}")
                when {
                    serverFailed -> state.setLastError(WearDownloadError.SERVER_NO_PHONE_COPY)
                    else -> state.setLastError(WearDownloadError.PHONE_NO_COPY)
                }
                true
            }
            is DownloadOutcome.NoSpace -> {
                state.clearPhoneUnreachable()
                // Like a file of an episode (review2 J7, review3 Q33): with its real size the planner only queues it again once
                // that much room is free; with the estimate it queued it right away, and every transfer was refused at the header.
                Log.i(TAG, "$uid does not fit (${outcome.totalBytes ?: "?"} bytes)")
                outcome.totalBytes?.let { state.recordSongSize(uid, it) }
                downloadDao.delete(listOf(uid))
                storageFull()
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Phone unreachable for $uid (attempt ${state.retryRuns + 1})", outcome.cause)
                downloadDao.requeue(uid)
                state.setLastError(WearDownloadError.PHONE_UNREACHABLE)
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /**
     * For each album with downloaded songs, the cover once as a file (Wear concept 8.2), likewise the image of every downloaded episode
     * and every downloaded book (16.1/16.2, in the cache of [de.kleinabersmart.earloft.core.data.spoken.BookCovers]; episodes without their own image show the subscription's).
     * What the watch cannot load itself (files from the phone's device, server unreachable) is sent by the phone.
     * If it is missing, playback works anyway.
     */
    private suspend fun cacheCovers(serversOnly: Boolean = false) {
        // Once per book, even with several files.
        for (bookId in (podcastDao.downloadedTracks() + bookDao.downloadedBookTracks()).map { it.bookId }.distinct()) {
            try {
                val key = WatchState.bookCoverKey(bookId)
                if (edges.ensureBookCover(bookId)) {
                    state.clearCoverMissing(key)
                } else if (!serversOnly && !state.coverRecentlyMissing(key)) {
                    // A channel to the phone per book on every run: only again after a while if the phone had none either.
                    val cover = edges.coverFromPhone(bookId)
                    if (cover != null) edges.storeBookCover(bookId, cover) else state.recordCoverMissing(key)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Image of $bookId not loadable", e)
            }
        }
        for (albumId in downloadDao.getAlbumIdsWithDoneDownload()) {
            try {
                val key = WatchState.albumCoverKey(albumId)
                if (edges.cacheAlbumCover(albumId, COVER_SIZE_PX)) state.clearCoverMissing(key) else if (!serversOnly) state.recordCoverMissing(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover for album $albumId not loadable", e)
            }
        }
    }

    private suspend fun finish(): Result {
        storage.deleteOrphans(downloadDao.getAll())
        // Episodes no longer in the manifest are deleted along with their entry by the sync; here their files disappear.
        entryPoint.episodeStorage().deleteOrphans(
            podcastDao.downloadedTracks().mapNotNull { it.filePath },
            podcastDao.pendingTracks().map { it.bookId },
        )
        val bookStorage = entryPoint.bookStorage()
        bookStorage.deleteOrphans(bookDao.downloadedBookTracks().mapNotNull { it.filePath })
        // Partial files only when nothing is waiting any more: otherwise the next run resumes there.
        if (bookDao.countPendingBookDownloads() == 0) bookStorage.deleteParts()
        coverFiles.retainOnly(downloadDao.getAlbumIdsWithDoneDownload().toSet())
        state.clearRetries()
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /**
     * Without foreground permission (started from the background, e.g. the retry after backoff) downloading simply
     * continues normally and is not asked again in this run – otherwise every percent raised an exception with a stack trace.
     * Then the time limit for jobs applies; whatever is cut off is resumed by the next run (everything from the phone, books from the server).
     */
    private var foregroundDenied = false

    private suspend fun startForeground() = notify(progress = null)

    private suspend fun notify(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(pendingCount(), progress)))
        } catch (e: CancellationException) {
            // Subclass of IllegalStateException: the worker was stopped, the foreground was not denied (Review V28).
            throw e
        } catch (e: IllegalStateException) {
            foregroundDenied = true
            Log.i(TAG, "Foreground start not possible, downloading in the background (${e.javaClass.simpleName})")
        }
    }

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun buildNotification(pending: Int, progress: Int?): Notification {
        val manager = NotificationManagerCompat.from(applicationContext)
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(applicationContext.getString(R.string.download_channel_name))
                .build(),
        )
        val text = if (pending > 0) {
            applicationContext.resources.getQuantityString(R.plurals.download_notification_pending, pending, pending)
        } else {
            null
        }
        val openStorage = storageIntent()
        val builder = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openStorage)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        // Ongoing Activity (WO-V4): icon on the watch face and entry under "Recent" while downloading; tapping it
        // leads to Storage & downloads. The status line is the notification text ("N waiting").
        if (openStorage != null) {
            OngoingActivity.Builder(applicationContext, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.ic_download)
                .setTouchIntent(openStorage)
                .build()
                .apply(applicationContext)
        }
        return builder.build()
    }

    /** Opens the app at Storage & downloads; own request code, otherwise it would overwrite the playback intent. */
    private fun storageIntent(): PendingIntent? {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName) ?: return null
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra(EXTRA_OPEN_STORAGE, true)
        return PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /** Database, files and state; whatever leaves the watch goes through [edges]. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface WearDownloadEntryPoint {
        fun downloadDao(): DownloadDao
        fun wearDao(): WearDao
        fun storage(): DownloadStorage
        fun state(): WatchState
        fun coverFiles(): CoverFiles
        fun scheduler(): WearDownloadScheduler
        fun podcastDao(): PodcastDao
        fun episodeStorage(): EpisodeStorage
        fun bookDao(): BookDao
        fun bookStorage(): BookStorage
        fun edges(): WearDownloadEdges
    }

    companion object {
        /** Input: the job only runs on the charger ([WearDownloadScheduler.enqueueOnCharger]); retries keep that. */
        internal const val KEY_ON_CHARGER = "on_charger"

        private const val TAG = "WearDownloadWorker"
        private const val CHANNEL_ID = "wear-downloads"
        private const val NOTIFICATION_ID = 2002
        private const val COVER_SIZE_PX = 400

        /** With [retryDelayMs] from 30 s, roughly a quarter of an hour. */
        private const val MAX_WIFI_ATTEMPTS = 5

        /** An access token that expires in less than 5 minutes is replaced before the run (concept 12.11). */
        private const val KEY_MARGIN_SECONDS = 5 * 60L
        private const val KEY_WAIT_MS = 15_000L
        private const val KEY_POLL_MS = 500L
    }
}

/** Delay before the [runs]-th consecutive retry: 30 s, 1, 2, 4 … min, at most 5 h (like WorkManager's backoff used to be). */
internal fun retryDelayMs(runs: Int): Long =
    (RETRY_BASE_MS shl (runs.coerceAtLeast(1) - 1).coerceAtMost(20)).coerceAtMost(RETRY_MAX_MS)

private const val RETRY_BASE_MS = 30_000L
private const val RETRY_MAX_MS = 5 * 60 * 60 * 1000L

internal enum class PhoneWaitStep { RETRY, RETRY_ON_CHARGER, WAIT_FOR_CHARGER }

/**
 * Next step when the phone was unreachable in the [runs]-th run (Review V1): retry with backoff up to
 * [WatchState.PHONE_ATTEMPTS], then wait for the charger. If the watch is already on power, it stays with the backoff.
 */
internal fun phoneWaitStep(runs: Int, pluggedIn: Boolean): PhoneWaitStep = when {
    runs < WatchState.PHONE_ATTEMPTS -> PhoneWaitStep.RETRY
    pluggedIn -> PhoneWaitStep.RETRY_ON_CHARGER
    else -> PhoneWaitStep.WAIT_FOR_CHARGER
}

/** Failed attempts of episodes are stored next to those of songs ([WatchState]); own key so they do not mix. */
private fun episodeFailureKey(bookId: Long) = "episode:$bookId"

/** Like [episodeFailureKey], per book (16.2). */
private fun bookFailureKey(bookId: Long) = "book:$bookId"

/** A book file from the phone's device: its identifier is that of the file channel ([de.kleinabersmart.earloft.wear.sync.PodcastMirror]). */
private val BookTrackEntity.isPhoneOnlyBook: Boolean get() = WearBookFile.parse(remoteFileId) != null

/** An episode from the phone's device: its identifier is that of the file channel, there is no address ([de.kleinabersmart.earloft.wear.sync.PodcastMirror]). */
private val BookTrackEntity.isPhoneOnly: Boolean get() = WearEpisodeFile.parse(remoteFileId) != null

private fun WearQuality.toProfile(): DownloadProfile = when (this) {
    WearQuality.OPUS_128 -> DownloadProfile.OPUS_128
    WearQuality.ORIGINAL -> DownloadProfile.ORIGINAL
}
