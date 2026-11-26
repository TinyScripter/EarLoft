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
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastTranscripts
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Works through the queue of episode downloads, newest episode first and [PARALLEL] at a time. Structured like the
 * [DownloadWorker]: foreground worker with progress, network errors put the entry back and retry with backoff,
 * then from the point where the partial file ends.
 */
class EpisodeDownloadWorker internal constructor(
    context: Context,
    params: WorkerParameters,
    entryPoint: EpisodeEntryPoint,
) : CoroutineWorker(context, params) {

    constructor(context: Context, params: WorkerParameters) :
        this(context, params, EntryPointAccessors.fromApplication(context, EpisodeEntryPoint::class.java))

    private val dao = entryPoint.podcastDao()
    private val edges = entryPoint.episodeEdges()
    private val storage = entryPoint.episodeStorage()
    private val progress = entryPoint.episodeProgress()
    private val space = entryPoint.space()

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
        // The job only starts while the storage is not low: whatever waited for room may try again.
        space.enough(DownloadKind.EPISODES)
        if (dao.countPendingDownloads() == 0) return finish()
        startForeground(progress = null)

        // A network error ends the run: nobody takes a new episode afterwards, the worker retries with backoff.
        val retry = AtomicBoolean(false)
        try {
            coroutineScope {
                repeat(PARALLEL) {
                    launch {
                        while (!retry.get() && !noRoom.get()) {
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
        } catch (e: CancellationException) {
            if (stoppedForLowStorage) {
                // Android stopped the job for low storage: a partial file would only keep it low (review H7). The episodes
                // stay queued and start over once there is room; the downloads page says why nothing happens meanwhile.
                withContext(NonCancellable) {
                    storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, emptyList())
                    space.short(DownloadKind.EPISODES)
                }
            }
            throw e
        }
        return if (retry.get()) Result.retry() else finish()
    }

    /** An episode did not fit: no new ones in this run, the next starts once there is room ([DownloadSpace.retryLater]). */
    private val noRoom = AtomicBoolean(false)

    private suspend fun takeNext(): BookTrackEntity? = pick.withLock {
        dao.nextQueuedDownloads(PARALLEL + 1).firstOrNull { it.bookId !in active }?.also { active += it.bookId }
    }

    /** @return `false` if the run should be retried with backoff. */
    private suspend fun load(next: BookTrackEntity): Boolean {
        progress.report(next.bookId, 0, null)
        val outcome = try {
            edges.download(next) { done, total ->
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
                Log.w(TAG, "Episode ${next.bookId} interrupted (attempt ${runAttemptCount + 1})", outcome.cause)
                if (!budget.giveUp(outcome)) {
                    dao.requeueDownload(next.bookId)
                    return false
                }
                dao.markDownloadFailed(next.bookId, outcome.failure)
                storage.deletePart(next.bookId, 0)
            }
            is DownloadOutcome.Failed -> Log.w(TAG, "Episode ${next.bookId} failed: ${outcome.reason}")
            is DownloadOutcome.NoSpace -> {
                // Stays queued; the other episodes wait too instead of trying one after another (review H7).
                Log.w(TAG, "Episode ${next.bookId}: not enough storage (${outcome.totalBytes ?: "?"} bytes), waiting for room")
                dao.requeueDownload(next.bookId)
                if (!noRoom.getAndSet(true)) {
                    space.short(DownloadKind.EPISODES)
                    space.retryLater(DownloadKind.EPISODES)
                }
            }
            DownloadOutcome.Done -> edges.downloaded(next.bookId)
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
            Log.i(TAG, "Foreground start not possible, downloading in the background (${e.javaClass.simpleName})")
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

    /** Database, files and state; the feed hosts and what follows a download go through [episodeEdges]. */
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    internal interface EpisodeEntryPoint {
        fun podcastDao(): PodcastDao
        fun episodeStorage(): EpisodeStorage
        fun episodeProgress(): EpisodeDownloadProgress
        fun space(): DownloadSpace
        fun episodeEdges(): EpisodeDownloadEdges
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

/**
 * The ways out of the [EpisodeDownloadWorker]: the feed hosts and what follows a finished download. What the worker decides on
 * top of them (parallel runs, retries, running out of room) is tested against a fake, with the real database and files
 * (review2 section 5).
 */
internal interface EpisodeDownloadEdges {
    /** Like [EpisodeDownloader.download]: updates the state of the episode itself. */
    suspend fun download(track: BookTrackEntity, onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit): DownloadOutcome

    /** After a download: chapters from the file, the transcript for offline use and the automation's bookkeeping. */
    suspend fun downloaded(bookId: Long)
}

internal class EpisodeDownloadEdgesImpl @Inject constructor(
    private val downloader: EpisodeDownloader,
    private val podcasts: PodcastRepository,
    private val transcripts: PodcastTranscripts,
    private val downloads: EpisodeDownloads,
) : EpisodeDownloadEdges {
    override suspend fun download(track: BookTrackEntity, onProgress: suspend (Long, Long?) -> Unit) =
        downloader.download(track, onProgress = onProgress)

    override suspend fun downloaded(bookId: Long) {
        // If the feed lists no chapters, they may be in the file (concept 12.5 "chapter sources").
        podcasts.chaptersFromFile(bookId)
        // Fetch the transcript too, so it's available offline (concept 12.5 "transcripts").
        transcripts.prefetch(bookId)
        downloads.onDownloaded(bookId)
    }
}

@Module
@InstallIn(SingletonComponent::class)
internal interface EpisodeDownloadEdgesModule {
    @Binds
    fun bindEpisodeDownloadEdges(edges: EpisodeDownloadEdgesImpl): EpisodeDownloadEdges
}
