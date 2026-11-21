package de.kleinabersmart.earloft.core.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.backup.UserDataBackup
import de.kleinabersmart.earloft.core.data.podcast.HEARD_UNDO_GRACE_MS
import de.kleinabersmart.earloft.core.data.podcast.HeardDownload
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.podcast.expiredHeardDownloads
import de.kleinabersmart.earloft.core.database.dao.FinishedDownloadRow
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** When a manually requested download starts. */
enum class DownloadStart { STARTED, WAITING_FOR_WIFI, OFFLINE }

/** Fully downloaded file of an episode; [mimeType] may be missing. */
data class DownloadedEpisodeFile(val file: File, val mimeType: String?)

/**
 * Podcast episode downloads: manually per episode, automatically for the newest unplayed episodes of a podcast with
 * "Download new episodes automatically" switched on, and cleanup. During playback the file replaces the stream
 * (`BookPlaybackSource`).
 */
@Singleton
class EpisodeDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: PodcastDao,
    private val podcasts: PodcastRepository,
    private val settings: PodcastSettings,
    private val storage: EpisodeStorage,
    private val userData: UserDataBackup,
) {
    private val started = AtomicBoolean(false)

    /** The episode or book loaded in the player (from [start]); its file is kept when cleaning up played episodes. */
    private var loadedBookId: Flow<Long?> = flowOf(null)

    /**
     * Enqueues the episode if it isn't downloaded or downloading yet. The result tells whether the download starts
     * right away or waits for a connection (the user should be told).
     */
    suspend fun download(bookId: Long): DownloadStart {
        val mobileAllowed = settings.useMobileData.first()
        if (dao.queueDownload(bookId) > 0) enqueueWorker(WorkManager.getInstance(context).manualDownloadPolicy(WORK_NAME))
        return connectionState(mobileAllowed)
    }

    /** Another run for what is waiting, e.g. after it did not fit ([SpaceRetryWorker]). */
    internal suspend fun resume() {
        if (dao.countPendingDownloads() > 0) enqueueWorker()
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

    /** The fully downloaded file of the episode including media type (for sharing); `null` while it's missing or still downloading. */
    suspend fun downloadedFile(bookId: Long): DownloadedEpisodeFile? {
        val track = dao.trackOf(bookId) ?: return null
        val file = track.filePath?.let(::File)?.takeIf { it.isFile } ?: return null
        return DownloadedEpisodeFile(file, track.mimeType)
    }

    /** Deletes the episode's file (including a waiting or running download). The automation doesn't download it again. */
    suspend fun remove(bookId: Long) {
        val track = dao.trackOf(bookId)
        dao.markDownloadRemoved(bookId)
        storage.delete(bookId, track?.filePath)
    }

    /** Deletes the files of all downloaded episodes of a podcast. @return count. */
    suspend fun removeFeed(feedId: String): Int = dao.downloadedEpisodeIds(feedId).onEach { remove(it) }.size

    /** Like [removeFeed], plus waiting and running downloads (when archiving a subscription). @return count. */
    suspend fun removeAllOf(feedId: String): Int = dao.episodeIdsWithDownload(feedId).onEach { remove(it) }.size

    /**
     * Deletes files without an episode (subscription ended, OPML replaced, episode dropped from the feed) – otherwise only the
     * next download cleans up. Only when nothing is downloading or waiting: a just-finished file wouldn't have its path yet. @return count.
     */
    suspend fun deleteOrphans(): Int {
        if (dao.countPendingDownloads() > 0) return 0
        // Nothing is waiting: nobody resumes partial files anymore.
        return storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, emptyList())
    }

    /** Deletes the files of all downloaded episodes. @return count. */
    suspend fun removeAll(): Int = dao.allDownloadedEpisodeIds().onEach { remove(it) }.size

    /** Deletes the files of all fully played episodes of the podcast. @return count. */
    suspend fun removeHeard(feedId: String): Int =
        dao.finishedDownloads(feedId).onEach { remove(it.bookId) }.size

    /**
     * Enqueues the newest unplayed episodes if the podcast downloads automatically. Before that, what the backup knows about the
     * episodes applies (played, download removed): after a device change they only show up again after the sync. Never for
     * archived and trial subscriptions, and not while a database import runs (review G26).
     */
    suspend fun queueAutomatic(feedId: String) {
        if (podcasts.automationPaused) return
        val feed = dao.feed(feedId)?.takeIf { it.autoDownload && it.subscribed && !it.archived } ?: return
        userData.applyWaiting()
        // "Only the newest" in Continue listening: keep only the newest ready as well; otherwise the subscription's count or the default.
        val newestOnly = feed.queueMode == FeedQueueMode.NEWEST_ONLY
        val count = if (newestOnly) 1 else feed.autoDownloadCount ?: settings.autoDownloadCount.first()
        if (dao.queueLatestUnheard(feedId, count) > 0) enqueueWorker()
        if (newestOnly) removeSuperseded(feedId)
    }

    /**
     * "Only the newest": older automatically downloaded, never started episodes go as soon as a newer one is finished (after
     * every sync and every finished download). Manually downloaded and started ones stay, as does the one in the player.
     */
    suspend fun removeSuperseded(feedId: String) {
        val loaded = loadedBookId.first()
        dao.supersededAutomaticDownloads(feedId).filter { it != loaded }.forEach { remove(it) }
    }

    /** After a finished download: with "Only the newest", remove the older automatically downloaded ones ([removeSuperseded]). */
    suspend fun onDownloaded(bookId: Long) {
        val feedId = dao.feedIdOf(bookId) ?: return
        val feed = dao.feed(feedId) ?: return
        if (feed.autoDownload && feed.queueMode == FeedQueueMode.NEWEST_ONLY) removeSuperseded(feedId)
    }

    /**
     * Attaches the automation to the syncs and the switch; once at app start. [loadedBookId]: what is loaded in the
     * player – an episode already counts as played when paused shortly before the end, but can still be continued.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, loadedBookId: Flow<Long?> = flowOf(null)) {
        if (!started.compareAndSet(false, true)) return
        this.loadedBookId = loadedBookId
        scope.launch {
            merge(
                podcasts.refreshed,
                // Switched on, count changed or "Only the newest" chosen: only the changed subscriptions. The first list is
                // compared against an empty map on purpose, so every process start checks all automatic subscriptions once and
                // catches up on downloads a crash or a full disk left out (user's decision, review G38); without anything
                // missing that's one query per subscription.
                podcasts.feeds
                    .map { feeds -> feeds.filter { it.autoDownload }.associate { it.id to (it.autoDownloadCount to it.queueMode) } }
                    .distinctUntilChanged()
                    .runningFold(emptyMap<String, Any?>() to emptyMap<String, Any?>()) { last, now -> last.second to now }
                    .flatMapConcat { (before, now) -> now.filter { (id, value) -> before[id] != value }.keys.asFlow() },
            ).collect { queueAutomatic(it) }
        }
        // A larger count immediately fetches missing episodes (a smaller one deletes nothing).
        scope.launch {
            settings.autoDownloadCount.distinctUntilChanged().drop(1).collect {
                dao.feeds().filter { it.autoDownload }.forEach { queueAutomatic(it.feedId) }
            }
        }
        // Downloads re-enqueued from the backup.
        scope.launch { userData.downloadsQueued.collect { enqueueWorker() } }
        // The "mobile data" setting applies to waiting downloads immediately: replace the job with new constraints.
        scope.launch {
            settings.useMobileData.distinctUntilChanged().drop(1).collect {
                if (dao.countPendingDownloads() > 0) enqueueWorker(ExistingWorkPolicy.REPLACE)
            }
        }
        // Fully played episodes: the download expires after the configured time. While the app is running, every
        // change of the progress, the setting and the loaded track is checked ("immediately" deletes at the end of the episode or
        // as soon as it's no longer in the player); expired periods are cleaned up by the daily HeardCleanupWorker.
        scope.launch {
            // Distinct: the list is re-read on every saved position, its content changes only when an episode is finished
            // (review G39); otherwise every save read the file date of every finished download.
            combine(dao.observeFinishedDownloads().distinctUntilChanged(), settings.deleteHeardAfterDays, loadedBookId, ::Triple)
                .distinctUntilChanged()
                .collectLatest { (finished, days, loaded) ->
                    removeExpired(finished, days, loaded)
                    // "Immediately" waits until "Undo" is no longer possible; if that brings the episode back, [finished] changes first.
                    if (days == 0 && finished.isNotEmpty()) {
                        delay(HEARD_UNDO_GRACE_MS)
                        removeExpired(finished, days, loaded)
                    }
                }
        }
        scope.launch { HeardCleanupWorker.schedule(context) }
        scope.launch {
            // After a process death entries may still be RUNNING/QUEUED. KEEP: if the worker itself started the process,
            // a second one would otherwise attach to it and then find nothing.
            if (dao.countPendingDownloads() > 0) enqueueWorker(ExistingWorkPolicy.KEEP)
        }
    }

    /** Deletes the downloads of fully played episodes whose period has expired. @return count. */
    suspend fun removeExpiredHeard(): Int =
        removeExpired(dao.observeFinishedDownloads().first(), settings.deleteHeardAfterDays.first(), loadedBookId.first())

    private suspend fun removeExpired(finished: List<FinishedDownloadRow>, days: Int, loaded: Long?): Int {
        // The file is finished when it was last written (renaming doesn't change the time).
        val heard = finished.filter { it.bookId != loaded }.map { HeardDownload(it.bookId, it.finishedAt, it.filePath?.let(::File)?.lastModified() ?: 0L) }
        return expiredHeardDownloads(heard, days, System.currentTimeMillis()).onEach { remove(it) }.size
    }

    private suspend fun enqueueWorker(policy: ExistingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE) {
        val mobile = settings.useMobileData.first()
        val request = OneTimeWorkRequestBuilder<EpisodeDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobile) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "episode-downloads"
    }
}
