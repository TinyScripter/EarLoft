package de.kleinabersmart.earloft.wear.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.CoverFiles
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.dao.WearDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.BookDownloader
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadProfile
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.Downloader
import de.kleinabersmart.earloft.core.download.EpisodeDownloader
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.download.ProgressThrottle
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearQuality
import de.kleinabersmart.earloft.wear.R
import de.kleinabersmart.earloft.wear.sync.CredentialsRequester
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Lädt die eingereihten Songs der Auswahlen vom Server (Wear-Konzept 8.2): nur über ein ausdrücklich
 * angefordertes WLAN, nach Einstellung nur am Ladegerät (Bedingung des Auftrags, siehe [WearDownloadScheduler]).
 * Ohne WLAN versucht WorkManager es später mit Backoff erneut, nach [MAX_WIFI_ATTEMPTS] Versuchen erst am Ladegerät. Songs, die nur das Handy hat, und Songs, bei
 * denen der Server dreimal gescheitert ist, kommen über das Handy ([PhoneFileFetcher], Konzept 8.3/8.4).
 * Nach der Musik die Podcast-Folgen (16.1): direkt aus dem Feed über dasselbe WLAN, Folgen vom Gerät des Handys und nach
 * drei Fehlversuchen über das Handy. Danach die Hörbücher (16.2): direkt von Audiobookshelf über dasselbe WLAN (eine
 * abgebrochene Datei macht beim nächsten Lauf weiter), Bücher vom Gerät des Handys und nach drei Fehlversuchen über das Handy.
 */
class WearDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, WearDownloadEntryPoint::class.java)
    private val downloadDao = entryPoint.downloadDao()
    private val wearDao = entryPoint.wearDao()
    private val downloader = entryPoint.downloader()
    private val storage = entryPoint.storage()
    private val state = entryPoint.state()
    private val artwork = entryPoint.artwork()
    private val coverFiles = entryPoint.coverFiles()
    private val phoneFetcher = entryPoint.phoneFetcher()
    private val podcastDao = entryPoint.podcastDao()
    private val episodeDownloader = entryPoint.episodeDownloader()
    private val bookDao = entryPoint.bookDao()

    override suspend fun doWork(): Result {
        downloadDao.requeueRunning()
        podcastDao.requeueRunningDownloads()
        bookDao.requeueRunningBookDownloads()
        val coversMissing = downloadDao.getAlbumIdsWithDoneDownload().any { !coverFiles.has(it) }
        if (pendingCount() == 0 && !coversMissing) return finish()
        if (!hasRoomToStart()) return finish()
        startForeground()

        // Songs, die nur das Handy hat, brauchen kein WLAN.
        if (!drainPhoneQueue()) return Result.retry()

        var lease: WifiLease? = null
        try {
            val profile = state.settings.value.quality.toProfile()
            while (true) {
                val next = downloadDao.nextQueuedFromServers() ?: break
                val uid = next.sourceUid
                if (state.hasServerFailedTooOften(uid)) {
                    // Der Server hat es schon mehrmals nicht geschafft (Wechsel an anderer Stelle, später erneut versucht).
                    if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    continue
                }
                lease = lease ?: entryPoint.wifi().acquire()
                if (lease == null) return waitForWifi()
                state.setWaitingForWifi(false)
                val throttle = ProgressThrottle()
                val outcome = downloader.download(next, profile, lease.network) { done, total ->
                    val percent = throttle.next(done, total)
                    if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
                }
                when (outcome) {
                    is DownloadOutcome.Retry -> {
                        val failures = state.recordServerFailure(uid)
                        // Ein nicht erreichbarer Server trifft alle wartenden Songs von ihm: Sonst brauchte jeder drei eigene
                        // Läufe, und der Backoff von WorkManager wüchse dabei weiter.
                        val sourceId = downloadDao.getSongSource(uid)?.sourceId
                        if (sourceId != null) {
                            state.recordServerFailures(downloadDao.queuedUidsOfSource(sourceId).filter { it != uid })
                            // Neues Passwort bzw. neuer Token am Handy: Die Antwort ersetzt die Zugangsdaten vor dem nächsten Versuch.
                            if (outcome.isAuthRejection) entryPoint.credentials().request(listOf(sourceId))
                        }
                        // Der Text steht ausdrücklich im Log: Log.w mit Throwable gibt bei UnknownHostException keinen Stacktrace aus.
                        Log.w(TAG, "Download $uid unterbrochen (Fehlversuch $failures von ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        if (failures < WatchState.SERVER_ATTEMPTS) {
                            downloadDao.requeue(uid)
                            return Result.retry()
                        }
                        if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    }
                    is DownloadOutcome.Failed -> {
                        Log.w(TAG, "Download $uid fehlgeschlagen: ${outcome.reason}")
                        // Datei weg, Anmeldung abgelehnt o. Ä.: Vielleicht hat das Handy sie.
                        state.setLastError(outcome.reason)
                        if (!viaPhone(next, serverFailed = true)) return Result.retry()
                    }
                    DownloadOutcome.Done -> {
                        state.clearServerFailures(uid)
                        state.setLastError(null)
                    }
                    DownloadOutcome.Cancelled -> Unit
                }
            }
            while (true) {
                val track = podcastDao.nextQueuedDownload() ?: break
                val key = episodeFailureKey(track.bookId)
                if (track.isPhoneOnly || state.hasServerFailedTooOften(key)) {
                    if (!episodeViaPhone(track)) return Result.retry()
                    continue
                }
                lease = lease ?: entryPoint.wifi().acquire()
                if (lease == null) return waitForWifi()
                state.setWaitingForWifi(false)
                val network = lease.network
                val outcome = downloadSpoken(track) { onProgress -> episodeDownloader.download(track, network, onProgress) }
                when (outcome) {
                    is DownloadOutcome.Retry -> {
                        val failures = state.recordServerFailure(key)
                        Log.w(TAG, "Folge ${track.bookId} unterbrochen (Fehlversuch $failures von ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        if (failures < WatchState.SERVER_ATTEMPTS) {
                            podcastDao.requeueDownload(track.bookId)
                            return Result.retry()
                        }
                        if (!episodeViaPhone(track)) return Result.retry()
                    }
                    is DownloadOutcome.Failed -> {
                        // Adresse weg, keine Audiodatei o. Ä.: Vielleicht hat das Handy sie geladen.
                        Log.w(TAG, "Folge ${track.bookId} fehlgeschlagen: ${outcome.reason}")
                        if (!episodeViaPhone(track)) return Result.retry()
                    }
                    DownloadOutcome.Done -> state.clearServerFailures(key)
                    DownloadOutcome.Cancelled -> Unit
                }
            }
            if (bookDao.nextQueuedBookTrack() != null) renewExpiringBookKeys()
            while (true) {
                val track = bookDao.nextQueuedBookTrack() ?: break
                val key = bookFailureKey(track.bookId)
                if (track.isPhoneOnlyBook || state.hasServerFailedTooOften(key)) {
                    if (!bookViaPhone(track)) return Result.retry()
                    continue
                }
                val book = bookDao.book(track.bookId) ?: break
                // Bücher aus ARD Sounds lädt die Uhr wie Folgen direkt von ihrer Adresse, ohne Schlüssel.
                if (!isArdSoundsBook(book.serverId) && entryPoint.audiobookshelf().server(book.serverId) == null) {
                    // Der Schlüssel ist noch unterwegs (Anfrage beim Handy läuft) oder fehlt dort; zählt als Fehlversuch.
                    val failures = state.recordServerFailure(key)
                    Log.w(TAG, "Buch ${track.bookId}: Audiobookshelf ohne Schlüssel (Fehlversuch $failures von ${WatchState.SERVER_ATTEMPTS})")
                    if (failures < WatchState.SERVER_ATTEMPTS) return Result.retry()
                    if (!bookViaPhone(track)) return Result.retry()
                    continue
                }
                lease = lease ?: entryPoint.wifi().acquire()
                if (lease == null) return waitForWifi()
                state.setWaitingForWifi(false)
                val network = lease.network
                val outcome = downloadSpoken(track) { onProgress -> entryPoint.bookDownloader().download(track, network, onProgress) }
                when (outcome) {
                    is DownloadOutcome.Retry -> {
                        val failures = state.recordServerFailure(key)
                        Log.w(TAG, "Buch ${track.bookId} Datei ${track.trackIndex} unterbrochen (Fehlversuch $failures von ${WatchState.SERVER_ATTEMPTS}): ${outcome.cause}", outcome.cause)
                        // Die Teildatei bleibt; der nächste Lauf macht dort weiter.
                        bookDao.requeueBookTrack(track.bookId, track.trackIndex)
                        // Neuer Schlüssel am Handy: Die Antwort ersetzt ihn vor dem nächsten Versuch.
                        if (outcome.isAuthRejection) entryPoint.credentials().request(listOf(book.serverId))
                        if (failures < WatchState.SERVER_ATTEMPTS) return Result.retry()
                        if (!bookViaPhone(track)) return Result.retry()
                    }
                    is DownloadOutcome.Failed -> {
                        // Datei weg, Schlüssel abgelehnt o. Ä.: Vielleicht hat das Handy das Buch geladen.
                        Log.w(TAG, "Buch ${track.bookId} fehlgeschlagen: ${outcome.reason}")
                        if (!bookViaPhone(track)) return Result.retry()
                    }
                    DownloadOutcome.Done -> state.clearServerFailures(key)
                    DownloadOutcome.Cancelled -> Unit
                }
            }
        } finally {
            lease?.close()
        }
        cacheCovers()
        return finish()
    }

    /**
     * Bei Anmeldung per OpenID gilt der Access-Token vom Handy höchstens eine Stunde (Konzept 12.11): Läuft er bald ab, vorher
     * einen frischen anfragen und kurz auf die Antwort warten, statt einen Fehlversuch mit 401 zu verbrauchen. Antwortet das
     * Handy nicht, geht es mit dem alten weiter; eine Ablehnung fragt dann wie bisher erneut.
     */
    private suspend fun renewExpiringBookKeys() {
        val audiobookshelf = entryPoint.audiobookshelf()
        val expiring = audiobookshelf.withExpiringToken(KEY_MARGIN_SECONDS)
        if (expiring.isEmpty()) return
        Log.i(TAG, "Schlüssel für ${expiring.size} Audiobookshelf-Server läuft ab, frischen beim Handy anfragen")
        entryPoint.credentials().request(expiring)
        withTimeoutOrNull(KEY_WAIT_MS) {
            while (audiobookshelf.withExpiringToken(KEY_MARGIN_SECONDS).any { it in expiring }) delay(KEY_POLL_MS)
        }
    }

    /** Kein WLAN zählt nicht als Fehlversuch des Servers: Sonst käme die ganze Auswahl über Bluetooth. */
    private fun waitForWifi(): Result {
        state.setWaitingForWifi(true)
        if (runAttemptCount + 1 < MAX_WIFI_ATTEMPTS) {
            Log.i(TAG, "Kein WLAN, später erneut (Versuch ${runAttemptCount + 1})")
            return Result.retry()
        }
        // Jeder Versuch startet einen Vordergrunddienst und schaltet das WLAN bis zu 30 s ein: nach einigen
        // Versuchen erst am Ladegerät weiter.
        Log.i(TAG, "Kein WLAN nach ${runAttemptCount + 1} Versuchen, weiter am Ladegerät")
        entryPoint.scheduler().enqueueOnCharger()
        return Result.success()
    }

    /**
     * Holt eine Folge über das Handy. @return `false`, wenn das Handy nicht erreichbar war; die Folge wartet dann weiter.
     * Hat das Handy keine Datei, bleibt die Folge fehlgeschlagen (neuer Versuch, wenn das Handy sie ins Manifest neu aufnimmt).
     */
    private suspend fun episodeViaPhone(track: BookTrackEntity): Boolean {
        return when (val outcome = downloadSpoken(track) { onProgress -> phoneFetcher.fetchEpisode(track, onProgress) }) {
            DownloadOutcome.Done -> {
                state.clearServerFailures(episodeFailureKey(track.bookId))
                true
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "Handy hat Folge ${track.bookId} nicht: ${outcome.reason}")
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Handy für Folge ${track.bookId} nicht erreichbar (Versuch ${runAttemptCount + 1})", outcome.cause)
                podcastDao.requeueDownload(track.bookId)
                state.setLastError(applicationContext.getString(R.string.download_error_phone_unreachable))
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /** Wie [episodeViaPhone] für eine Buchdatei. */
    private suspend fun bookViaPhone(track: BookTrackEntity): Boolean {
        return when (val outcome = downloadSpoken(track) { onProgress -> phoneFetcher.fetchBookTrack(track, onProgress) }) {
            DownloadOutcome.Done -> {
                state.clearServerFailures(bookFailureKey(track.bookId))
                true
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "Handy hat Buch ${track.bookId} Datei ${track.trackIndex} nicht: ${outcome.reason}")
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Handy für Buch ${track.bookId} nicht erreichbar (Versuch ${runAttemptCount + 1})", outcome.cause)
                bookDao.requeueBookTrack(track.bookId, track.trackIndex)
                state.setLastError(applicationContext.getString(R.string.download_error_phone_unreachable))
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /**
     * Lädt eine Datei einer Folge bzw. eines Buchs über [download] und meldet ihren Stand der Benachrichtigung und der
     * Oberfläche ([WatchState.spokenFileProgress], Knopf „Download“ im Bereich „Telefon“).
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

    private suspend fun pendingCount(): Int =
        downloadDao.countPending() + podcastDao.countPendingDownloads() + bookDao.countPendingBookDownloads()

    /**
     * Prüft vor dem Start den freien Speicher (Planung und Start liegen zeitlich auseinander). Passt nicht einmal der
     * kleinste wartende Titel unter die Grenze, bleibt alles eingereiht und der Auftrag endet ohne Fehlversuch: Der Planer
     * reiht nach dem nächsten Abgleich oder bei geänderten Einstellungen neu ein.
     */
    private suspend fun hasRoomToStart(): Boolean {
        val settings = state.settings.value
        val pending = wearDao.getPendingCandidates()
        val free = withContext(Dispatchers.IO) { storage.freeBytes() }
        val budget = downloadBudget(settings, free, wearDao.getDoneBytes() + bookDao.trackTotals().doneBytes, reservedBytes = 0)
        if (hasRoomForAny(pending.map { estimateBytes(it, settings.quality) }, budget)) return true
        Log.i(TAG, "Speicher voll: kein wartender Titel passt (Budget $budget Bytes), kein Start")
        state.setMissingForStorage(pending.size)
        return false
    }

    /**
     * Lädt alle wartenden Songs und Folgen, die nur das Handy hat. @return `false`, wenn das Handy fehlt und es später
     * erneut versucht werden soll.
     */
    private suspend fun drainPhoneQueue(): Boolean {
        while (true) {
            val next = downloadDao.nextQueuedFromPhone() ?: break
            if (!viaPhone(next, serverFailed = false)) return false
        }
        for (track in podcastDao.pendingTracks().filter { it.isPhoneOnly && it.downloadState == TrackDownload.QUEUED }) {
            if (!episodeViaPhone(track)) return false
        }
        for (track in bookDao.pendingBookTracks().filter { it.isPhoneOnlyBook && it.downloadState == TrackDownload.QUEUED }) {
            if (!bookViaPhone(track)) return false
        }
        return true
    }

    /**
     * Holt [download] im Original vom Handy (Wear-Konzept 8.4). [serverFailed]: Der Server hat es nicht geschafft
     * (sonst gibt es nur das Handy), das bestimmt den Fehlertext, wenn auch das Handy nichts hat.
     * @return `false`, wenn das Handy nicht erreichbar war; der Eintrag wartet dann weiter.
     */
    private suspend fun viaPhone(download: DownloadEntity, serverFailed: Boolean): Boolean {
        val uid = download.sourceUid
        // Zurück auf QUEUED: aus RUNNING (Netzfehler) bzw. FAILED (Server hat abgelehnt).
        downloadDao.requeue(uid)
        downloadDao.retryFailed(listOf(uid))
        val throttle = ProgressThrottle()
        return when (val outcome = phoneFetcher.fetch(download) { done, total ->
            val percent = throttle.next(done, total)
            if (percent != null) notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
        }) {
            DownloadOutcome.Done -> {
                state.clearServerFailures(uid)
                state.setLastError(null)
                true
            }
            is DownloadOutcome.Failed -> {
                Log.w(TAG, "Handy hat $uid nicht: ${outcome.reason}")
                state.setLastError(
                    applicationContext.getString(
                        if (serverFailed) R.string.download_error_server_no_phone_copy else R.string.download_error_phone_no_copy,
                    ),
                )
                true
            }
            is DownloadOutcome.Retry -> {
                Log.i(TAG, "Handy für $uid nicht erreichbar (Versuch ${runAttemptCount + 1})", outcome.cause)
                downloadDao.requeue(uid)
                state.setLastError(applicationContext.getString(R.string.download_error_phone_unreachable))
                false
            }
            DownloadOutcome.Cancelled -> true
        }
    }

    /**
     * Je Album mit geladenen Songs einmal das Cover als Datei (Wear-Konzept 8.2), ebenso das Bild jeder geladenen Folge
     * und jedes geladenen Buchs (16.1/16.2, im Cache von [BookCovers]; Folgen ohne eigenes Bild zeigen das des Abos).
     * Was die Uhr nicht selbst laden kann (Dateien vom Gerät des Handys, Server nicht erreichbar), schickt das Handy.
     * Fehlt es, geht die Wiedergabe trotzdem.
     */
    private suspend fun cacheCovers() {
        val bookCovers = entryPoint.bookCovers()
        // Je Buch einmal, auch bei mehreren Dateien.
        for (bookId in (podcastDao.downloadedTracks() + bookDao.downloadedBookTracks()).map { it.bookId }.distinct()) {
            try {
                if (!bookCovers.ensureCached(bookId)) phoneFetcher.fetchCover(bookId)?.let { bookCovers.store(bookId, it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bild von $bookId nicht ladbar", e)
            }
        }
        for (albumId in downloadDao.getAlbumIdsWithDoneDownload()) {
            try {
                artwork.cacheAlbumCover(albumId, COVER_SIZE_PX)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Cover für Album $albumId nicht ladbar", e)
            }
        }
    }

    private suspend fun finish(): Result {
        storage.deleteOrphans(downloadDao.getAll())
        // Folgen, die nicht mehr im Manifest stehen, löscht der Abgleich samt Eintrag; hier verschwinden ihre Dateien.
        entryPoint.episodeStorage().deleteOrphans(
            podcastDao.downloadedTracks().mapNotNull { it.filePath },
            podcastDao.pendingTracks().map { it.bookId },
        )
        val bookStorage = entryPoint.bookStorage()
        bookStorage.deleteOrphans(bookDao.downloadedBookTracks().mapNotNull { it.filePath })
        // Teildateien nur, wenn nichts mehr wartet: Sonst macht der nächste Lauf dort weiter.
        if (bookDao.countPendingBookDownloads() == 0) bookStorage.deleteParts()
        coverFiles.retainOnly(downloadDao.getAlbumIdsWithDoneDownload().toSet())
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /**
     * Ohne Vordergrund-Erlaubnis (Start aus dem Hintergrund, etwa die Wiederholung nach Backoff) wird einfach normal
     * weitergeladen und in diesem Lauf nicht erneut gefragt – sonst gab es bei jedem Prozent eine Ausnahme samt Stacktrace.
     * Dann gilt das Zeitlimit für Aufträge; was dabei abbricht, setzt der nächste Lauf fort (alles vom Handy, Bücher vom Server).
     */
    private var foregroundDenied = false

    private suspend fun startForeground() = notify(progress = null)

    private suspend fun notify(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(pendingCount(), progress)))
        } catch (e: IllegalStateException) {
            foregroundDenied = true
            Log.i(TAG, "Kein Vordergrund-Start möglich, lade im Hintergrund (${e.javaClass.simpleName})")
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
        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(applicationContext.getString(R.string.download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface WearDownloadEntryPoint {
        fun downloadDao(): DownloadDao
        fun wearDao(): WearDao
        fun downloader(): Downloader
        fun storage(): DownloadStorage
        fun wifi(): WifiNetwork
        fun state(): WatchState
        fun artwork(): ArtworkRepository
        fun coverFiles(): CoverFiles
        fun phoneFetcher(): PhoneFileFetcher
        fun scheduler(): WearDownloadScheduler
        fun podcastDao(): PodcastDao
        fun episodeDownloader(): EpisodeDownloader
        fun episodeStorage(): EpisodeStorage
        fun bookCovers(): BookCovers
        fun bookDao(): BookDao
        fun bookDownloader(): BookDownloader
        fun bookStorage(): BookStorage
        fun audiobookshelf(): AudiobookshelfServers
        fun credentials(): CredentialsRequester
    }

    private companion object {
        const val TAG = "WearDownloadWorker"
        const val CHANNEL_ID = "wear-downloads"
        const val NOTIFICATION_ID = 2002
        const val COVER_SIZE_PX = 400

        /** Bei exponentiellem Backoff ab 30 s rund eine Viertelstunde. */
        const val MAX_WIFI_ATTEMPTS = 5

        /** Ein Access-Token, der in weniger als 5 Minuten abläuft, wird vor dem Lauf ersetzt (Konzept 12.11). */
        const val KEY_MARGIN_SECONDS = 5 * 60L
        const val KEY_WAIT_MS = 15_000L
        const val KEY_POLL_MS = 500L
    }
}

/** Fehlversuche von Folgen stehen neben denen der Songs ([WatchState]); eigener Schlüssel, damit sie sich nicht mischen. */
private fun episodeFailureKey(bookId: Long) = "episode:$bookId"

/** Wie [episodeFailureKey], je Buch (16.2). */
private fun bookFailureKey(bookId: Long) = "book:$bookId"

/** Eine Datei eines Buchs vom Gerät des Handys: Ihre Kennung ist die des Datei-Kanals ([de.kleinabersmart.earloft.wear.sync.PodcastMirror]). */
private val BookTrackEntity.isPhoneOnlyBook: Boolean get() = WearBookFile.parse(remoteFileId) != null

/** Eine Folge vom Gerät des Handys: Ihre Kennung ist die des Datei-Kanals, eine Adresse gibt es nicht ([de.kleinabersmart.earloft.wear.sync.PodcastMirror]). */
private val BookTrackEntity.isPhoneOnly: Boolean get() = WearEpisodeFile.parse(remoteFileId) != null

private fun WearQuality.toProfile(): DownloadProfile = when (this) {
    WearQuality.OPUS_128 -> DownloadProfile.OPUS_128
    WearQuality.ORIGINAL -> DownloadProfile.ORIGINAL
}
