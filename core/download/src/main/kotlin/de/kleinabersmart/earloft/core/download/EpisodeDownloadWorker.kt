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
import de.kleinabersmart.earloft.core.data.podcast.PodcastTranscripts
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Works through the queue of episode downloads, newest episode first and [PARALLEL] at a time. Structured like the
 * [DownloadWorker]: foreground worker with progress, network errors put the entry back and retry with backoff,
 * then from the point where the partial file ends.
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
    private val episodeDownloads = entryPoint.episodeDownloads()
    private val transcripts = entryPoint.podcastTranscripts()

    /** Episodes one of the parallel runs has just taken (until the downloader sets them to `RUNNING`, they are still `QUEUED`). */
    private val active = HashSet<Long>()
    private val pick = Mutex()

    /** Progress of the running episodes (bytes, size) for a combined display in the notification. */
    private val running = ConcurrentHashMap<Long, Pair<Long, Long?>>()
    private val throttle = ProgressThrottle()

    /** Both runs report from their download's IO thread; [throttle] and the notification only one at a time. */
    private val report = Mutex()

    private val budget by lazy { RetryBudget(runAttemptCount, MAX_ATTEMPTS) }

    override suspend fun doWork(): Result {
        dao.requeueRunningDownloads()
        if (dao.countPendingDownloads() == 0) return finish()
        startForeground(progress = null)

        // A network error ends the run: nobody takes a new episode afterwards, the worker retries with backoff.
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

    /** @return `false` if the run should be retried with backoff. */
    private suspend fun load(next: BookTrackEntity): Boolean {
        progress.report(next.bookId, 0, null)
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
                if (!budget.giveUp(outcome)) {
                    dao.requeueDownload(next.bookId)
                    return false
                }
                dao.markDownloadFailed(next.bookId)
                storage.deletePart(next.bookId, 0)
            }
            is DownloadOutcome.Failed -> Log.w(TAG, "Folge ${next.bookId} fehlgeschlagen: ${outcome.reason}")
            // If the feed lists no chapters, they may be in the file (concept 12.5 "chapter sources").
            DownloadOutcome.Done -> {
                podcasts.chaptersFromFile(next.bookId)
                // Fetch the transcript too, so it's available offline (concept 12.5 "transcripts").
                transcripts.prefetch(next.bookId)
                episodeDownloads.onDownloaded(next.bookId)
            }
            DownloadOutcome.Cancelled -> Unit
        }
        return true
    }

    /** A percentage across the running episodes with known size; if none is known, indeterminate. */
    private suspend fun reportOverall() = report.withLock {
        val known = running.values.mapNotNull { (done, total) -> total?.let { done to it } }
        val total = known.sumOf { it.second }.takeIf { known.isNotEmpty() }
        val percent = throttle.next(known.sumOf { it.first }, total) ?: return@withLock
        notify(percent.takeIf { it != ProgressThrottle.UNKNOWN })
    }

    /**
     * Removes files that no longer have an entry (subscription ended, episode dropped from the feed). Partial files
     * of waiting episodes are kept for the next run.
     */
    private suspend fun finish(): Result {
        storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, dao.pendingTracks().map { it.bookId })
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(buildNotification(pending = 0, progress = null))

    /**
     * From the background (e.g. retry after backoff) the system refuses the foreground service; then downloading
     * continues normally and this run doesn't ask again (otherwise an exception plus stack trace at every percent).
     */
    @Volatile private var foregroundDenied = false

    private suspend fun startForeground(progress: Int?) {
        if (foregroundDenied) return
        try {
            setForeground(foregroundInfo(buildNotification(dao.countPendingDownloads(), progress)))
        } catch (e: CancellationException) {
            // Subclass of IllegalStateException: the worker was stopped, the foreground was not refused (review V28).
            throw e
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
        fun episodeDownloads(): EpisodeDownloads
        fun podcastTranscripts(): PodcastTranscripts
    }

    private companion object {
        const val TAG = "EpisodeDownloadWorker"

        /** The same channel as for the music downloads. */
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 2002
        const val MAX_ATTEMPTS = 5

        /** Episodes live on many hosts; two at a time use the connection better without overloading a server. */
        const val PARALLEL = 2
    }
}
