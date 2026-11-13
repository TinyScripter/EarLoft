package de.kleinabersmart.earloft.core.download

import android.app.Notification
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
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
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Arbeitet die Warteschlange der Folgen-Downloads ab, neueste Folge zuerst und [PARALLEL] zugleich. Aufbau wie beim
 * [DownloadWorker]: Vordergrund-Worker mit Fortschritt, Netzfehler stellen zurück und versuchen es mit Backoff erneut,
 * dann an der Stelle, an der die Teildatei endet.
 */
class EpisodeDownloadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    private val entryPoint = EntryPointAccessors.fromApplication(context, EpisodeEntryPoint::class.java)
    private val dao = entryPoint.podcastDao()
    private val downloader = entryPoint.episodeDownloader()
    private val storage = entryPoint.episodeStorage()
    private val progress = entryPoint.episodeProgress()
    private val podcasts = entryPoint.podcastRepository()

    /** Folgen, die einer der parallelen Läufe gerade genommen hat (bis der Downloader sie auf `RUNNING` setzt, stehen sie noch auf `QUEUED`). */
    private val active = HashSet<Long>()
    private val pick = Mutex()

    /** Fortschritt der laufenden Folgen (Bytes, Größe) für eine gemeinsame Anzeige in der Benachrichtigung. */
    private val running = ConcurrentHashMap<Long, Pair<Long, Long?>>()
    private val throttle = ProgressThrottle()

    override suspend fun doWork(): Result {
        dao.requeueRunningDownloads()
        if (dao.countPendingDownloads() == 0) return finish()
        startForeground(progress = null)

        // Ein Netzfehler beendet den Lauf: Keiner nimmt danach eine neue Folge, der Worker versucht es mit Backoff erneut.
        val retry = AtomicBoolean(false)
        coroutineScope {
            repeat(PARALLEL) {
                launch {
                    while (!retry.get()) {
                        val next = takeNext() ?: break
                        try {
                            if (!load(next)) retry.set(true)
                        } finally {
                            pick.withLock { active -= next.bookId }
                        }
                    }
                }
            }
        }
        return if (retry.get()) Result.retry() else finish()
    }

    private suspend fun takeNext(): BookTrackEntity? = pick.withLock {
        dao.nextQueuedDownloads(PARALLEL + 1).firstOrNull { it.bookId !in active }?.also { active += it.bookId }
    }

    /** @return `false`, wenn der Lauf mit Backoff wiederholt werden soll. */
    private suspend fun load(next: BookTrackEntity): Boolean {
        progress.report(next.bookId, 0, null)
        running[next.bookId] = 0L to null
        val outcome = try {
            downloader.download(next) { done, total ->
                progress.report(next.bookId, done, total)
                running[next.bookId] = done to total
                reportOverall()
            }
        } finally {
            progress.clear(next.bookId)
            running -= next.bookId
        }
        when (outcome) {
            is DownloadOutcome.Retry -> {
                Log.w(TAG, "Folge ${next.bookId} unterbrochen (Versuch ${runAttemptCount + 1})", outcome.cause)
                // Ein abgelehnter Zugang ändert sich durch Warten nicht.
                if (!outcome.isAuthRejection && runAttemptCount + 1 < MAX_ATTEMPTS) {
                    dao.requeueDownload(next.bookId)
                    return false
                }
                dao.markDownloadFailed(next.bookId)
                storage.deletePart(next.bookId, 0)
            }
            is DownloadOutcome.Failed -> Log.w(TAG, "Folge ${next.bookId} fehlgeschlagen: ${outcome.reason}")
            // Nennt der Feed keine Kapitel, stehen sie vielleicht in der Datei (Konzept 12.5 „Kapitelquellen“).
            DownloadOutcome.Done -> podcasts.chaptersFromFile(next.bookId)
            DownloadOutcome.Cancelled -> Unit
        }
        return true
    }

    /** Ein Prozentwert über alle laufenden Folgen; ohne bekannte Größe einer davon unbestimmt. */
    private suspend fun reportOverall() {
        val all = running.values.toList()
        val total = if (all.any { it.second == null }) null else all.sumOf { it.second ?: 0L }
        val percent = throttle.next(all.sumOf { it.first }, total) ?: return
        notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
    }

    /**
     * Räumt Dateien weg, zu denen es keinen Eintrag mehr gibt (Abo beendet, Folge aus dem Feed gefallen). Teildateien
     * wartender Folgen bleiben für den nächsten Lauf.
     */
    private suspend fun finish(): Result {
        storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, dao.pendingTracks().map { it.bookId })
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /** Ohne Vordergrund-Erlaubnis (Start aus dem Hintergrund) wird einfach normal weitergeladen. */
    /**
     * Aus dem Hintergrund (z. B. Wiederholung nach Backoff) verweigert das System den Vordergrunddienst; dann wird normal
     * weitergeladen und in diesem Lauf nicht erneut gefragt (sonst bei jedem Prozent eine Ausnahme samt Stacktrace).
     */
    private var foregroundDenied = false

    private suspend fun startForeground(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(dao.countPendingDownloads(), progress)))
        } catch (e: IllegalStateException) {
            foregroundDenied = true
            Log.i(TAG, "Kein Vordergrund-Start möglich, lade im Hintergrund (${e.javaClass.simpleName})")
        }
    }

    private suspend fun notify(progress: Int?) = startForeground(progress)

    private fun foregroundInfo(notification: Notification): ForegroundInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }

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
            .setSmallIcon(R.drawable.ic_stat_download)
            .setContentTitle(applicationContext.getString(R.string.episode_download_notification_title))
            .setContentText(text)
            .setProgress(100, progress ?: 0, progress == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EpisodeEntryPoint {
        fun podcastDao(): PodcastDao
        fun episodeDownloader(): EpisodeDownloader
        fun episodeStorage(): EpisodeStorage
        fun episodeProgress(): EpisodeDownloadProgress
        fun podcastRepository(): PodcastRepository
    }

    private companion object {
        const val TAG = "EpisodeDownloadWorker"

        /** Derselbe Kanal wie bei den Musik-Downloads. */
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2002
        const val MAX_ATTEMPTS = 5

        /** Folgen liegen auf vielen Hosts; zwei zugleich nutzen die Leitung besser, ohne einen Server zu überlasten. */
        const val PARALLEL = 2
    }
}
