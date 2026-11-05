package de.kleinabersmart.tinyamp.core.download

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
import de.kleinabersmart.tinyamp.core.data.podcast.HeardDownload
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastRepository
import de.kleinabersmart.tinyamp.core.data.podcast.PodcastSettings
import de.kleinabersmart.tinyamp.core.data.podcast.expiredHeardDownloads
import de.kleinabersmart.tinyamp.core.database.dao.FinishedDownloadRow
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/** Wann ein von Hand angeforderter Download beginnt. */
enum class DownloadStart { STARTED, WAITING_FOR_WIFI, OFFLINE }

/**
 * Downloads von Podcast-Folgen: von Hand je Folge, automatisch für die neuesten ungehörten Folgen eines Podcasts mit
 * eingeschaltetem „Neue Folgen automatisch herunterladen“, und Aufräumen. Die Datei ersetzt beim Abspielen den Stream
 * (`BookPlaybackSource`).
 */
@Singleton
class EpisodeDownloads @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dao: PodcastDao,
    private val podcasts: PodcastRepository,
    private val settings: PodcastSettings,
    private val storage: EpisodeStorage,
) {
    private val started = AtomicBoolean(false)

    /**
     * Reiht die Folge ein, falls sie noch nicht geladen ist oder wird. Das Ergebnis sagt, ob der Download gleich
     * beginnt oder auf eine Verbindung wartet (der Nutzer soll das erfahren).
     */
    suspend fun download(bookId: Long): DownloadStart {
        val mobileAllowed = settings.useMobileData.first()
        if (dao.queueDownload(bookId) > 0) enqueueWorker()
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

    /** Löscht die Datei der Folge (auch einen wartenden oder laufenden Download). Die Automatik lädt sie nicht neu. */
    suspend fun remove(bookId: Long) {
        val track = dao.trackOf(bookId)
        dao.markDownloadRemoved(bookId)
        storage.delete(bookId, track?.filePath)
    }

    /** Löscht die Dateien aller geladenen Folgen eines Podcasts. @return Anzahl. */
    suspend fun removeFeed(feedId: String): Int = dao.downloadedEpisodeIds(feedId).onEach { remove(it) }.size

    /** Wie [removeFeed], dazu wartende und laufende Downloads (beim Archivieren eines Abos). @return Anzahl. */
    suspend fun removeAllOf(feedId: String): Int = dao.episodeIdsWithDownload(feedId).onEach { remove(it) }.size

    /** Löscht die Dateien aller geladenen Folgen. @return Anzahl. */
    suspend fun removeAll(): Int = dao.allDownloadedEpisodeIds().onEach { remove(it) }.size

    /** Löscht die Dateien aller zu Ende gehörten Folgen des Podcasts. @return Anzahl. */
    suspend fun removeHeard(feedId: String): Int =
        dao.finishedDownloads(feedId).onEach { remove(it.bookId) }.size

    /** Reiht die neuesten ungehörten Folgen ein, wenn der Podcast automatisch lädt. */
    suspend fun queueAutomatic(feedId: String) {
        if (dao.feed(feedId)?.autoDownload != true) return
        if (dao.queueLatestUnheard(feedId, settings.autoDownloadCount.first()) > 0) enqueueWorker()
    }

    /** Hängt die Automatik an die Abgleiche und den Schalter; einmal beim Start der App. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            merge(
                podcasts.refreshed,
                podcasts.feeds
                    .map { feeds -> feeds.filter { it.autoDownload }.map { it.id }.toSet() }
                    .distinctUntilChanged()
                    .flatMapConcat { it.asFlow() },
            ).collect { queueAutomatic(it) }
        }
        // Eine größere Anzahl holt sofort fehlende Folgen nach (eine kleinere löscht nichts).
        scope.launch {
            settings.autoDownloadCount.distinctUntilChanged().drop(1).collect {
                dao.feeds().filter { it.autoDownload }.forEach { queueAutomatic(it.feedId) }
            }
        }
        // Die Einstellung „Mobilfunk“ gilt für wartende Downloads sofort: den Auftrag mit neuen Bedingungen ersetzen.
        scope.launch {
            settings.useMobileData.distinctUntilChanged().drop(1).collect {
                if (dao.countPendingDownloads() > 0) enqueueWorker(replace = true)
            }
        }
        // Zu Ende gehörte Folgen: der Download verfällt nach der eingestellten Zeit. Solange die App läuft, prüft jede
        // Änderung des Fortschritts und der Einstellung („sofort“ löscht beim Ende der Folge); abgelaufene Fristen
        // räumt der tägliche HeardCleanupWorker auf.
        scope.launch {
            combine(dao.observeFinishedDownloads(), settings.deleteHeardAfterDays, ::Pair)
                .collect { (finished, days) -> removeExpired(finished, days) }
        }
        scope.launch { HeardCleanupWorker.schedule(context) }
        scope.launch {
            // Nach einem Prozessende stehen Einträge evtl. noch auf RUNNING/QUEUED.
            if (dao.countPendingDownloads() > 0) enqueueWorker()
        }
    }

    /** Löscht die Downloads zu Ende gehörter Folgen, deren Frist abgelaufen ist. @return Anzahl. */
    suspend fun removeExpiredHeard(): Int =
        removeExpired(dao.observeFinishedDownloads().first(), settings.deleteHeardAfterDays.first())

    private suspend fun removeExpired(finished: List<FinishedDownloadRow>, days: Int): Int {
        // Die Datei ist fertig, wenn sie zuletzt geschrieben wurde (Umbenennen ändert die Zeit nicht).
        val heard = finished.map { HeardDownload(it.bookId, it.finishedAt, it.filePath?.let(::File)?.lastModified() ?: 0L) }
        return expiredHeardDownloads(heard, days, System.currentTimeMillis()).onEach { remove(it) }.size
    }

    private suspend fun enqueueWorker(replace: Boolean = false) {
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
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.APPEND_OR_REPLACE
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
    }

    private companion object {
        const val WORK_NAME = "episode-downloads"
    }
}
