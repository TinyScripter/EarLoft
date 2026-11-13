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
import de.kleinabersmart.earloft.core.data.podcast.HeardDownload
import de.kleinabersmart.earloft.core.data.podcast.PodcastRepository
import de.kleinabersmart.earloft.core.data.podcast.PodcastSettings
import de.kleinabersmart.earloft.core.data.podcast.expiredHeardDownloads
import de.kleinabersmart.earloft.core.database.dao.FinishedDownloadRow
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.model.FeedQueueMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flowOf
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
    private val userData: UserDataBackup,
) {
    private val started = AtomicBoolean(false)

    /** Die Folge bzw. das Buch, das im Player geladen ist (aus [start]); deren Datei bleibt beim Aufräumen gehörter Folgen. */
    private var loadedBookId: Flow<Long?> = flowOf(null)

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

    /**
     * Löscht Dateien ohne Folge (Abo beendet, OPML ersetzt, Folge aus dem Feed gefallen) – sonst räumt nur der nächste
     * Download auf. Nur, wenn nichts lädt oder wartet: Eine gerade fertige Datei hätte ihren Pfad noch nicht. @return Anzahl.
     */
    suspend fun deleteOrphans(): Int {
        if (dao.countPendingDownloads() > 0) return 0
        // Nichts wartet: Teildateien setzt keiner mehr fort.
        return storage.deleteOrphans(dao.downloadedTracks().mapNotNull { it.filePath }, emptyList())
    }

    /** Löscht die Dateien aller geladenen Folgen. @return Anzahl. */
    suspend fun removeAll(): Int = dao.allDownloadedEpisodeIds().onEach { remove(it) }.size

    /** Löscht die Dateien aller zu Ende gehörten Folgen des Podcasts. @return Anzahl. */
    suspend fun removeHeard(feedId: String): Int =
        dao.finishedDownloads(feedId).onEach { remove(it.bookId) }.size

    /**
     * Reiht die neuesten ungehörten Folgen ein, wenn der Podcast automatisch lädt. Vorher gilt, was die Sicherung zu den
     * Folgen weiß (gehört, Download entfernt): Nach einem Gerätewechsel stehen sie erst nach dem Abgleich wieder da.
     */
    suspend fun queueAutomatic(feedId: String) {
        val feed = dao.feed(feedId)?.takeIf { it.autoDownload } ?: return
        userData.applyWaiting()
        // „Nur die neueste“ in Weiterhören: auch nur die neueste bereithalten.
        val count = if (feed.queueMode == FeedQueueMode.NEWEST_ONLY) 1 else settings.autoDownloadCount.first()
        if (dao.queueLatestUnheard(feedId, count) > 0) enqueueWorker()
    }

    /**
     * Hängt die Automatik an die Abgleiche und den Schalter; einmal beim Start der App. [loadedBookId]: was im Player
     * geladen ist – eine Folge gilt schon beim Anhalten kurz vor dem Ende als gehört, lässt sich aber weiterhören.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start(scope: CoroutineScope, loadedBookId: Flow<Long?> = flowOf(null)) {
        if (!started.compareAndSet(false, true)) return
        this.loadedBookId = loadedBookId
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
        // Aus der Sicherung wieder eingereihte Downloads.
        scope.launch { userData.downloadsQueued.collect { enqueueWorker() } }
        // Die Einstellung „Mobilfunk“ gilt für wartende Downloads sofort: den Auftrag mit neuen Bedingungen ersetzen.
        scope.launch {
            settings.useMobileData.distinctUntilChanged().drop(1).collect {
                if (dao.countPendingDownloads() > 0) enqueueWorker(ExistingWorkPolicy.REPLACE)
            }
        }
        // Zu Ende gehörte Folgen: der Download verfällt nach der eingestellten Zeit. Solange die App läuft, prüft jede
        // Änderung des Fortschritts, der Einstellung und des geladenen Titels („sofort“ löscht beim Ende der Folge bzw.
        // sobald sie nicht mehr im Player liegt); abgelaufene Fristen räumt der tägliche HeardCleanupWorker auf.
        scope.launch {
            combine(dao.observeFinishedDownloads(), settings.deleteHeardAfterDays, loadedBookId, ::Triple)
                .collect { (finished, days, loaded) -> removeExpired(finished, days, loaded) }
        }
        scope.launch { HeardCleanupWorker.schedule(context) }
        scope.launch {
            // Nach einem Prozessende stehen Einträge evtl. noch auf RUNNING/QUEUED. KEEP: Hat der Worker selbst den Prozess
            // gestartet, hängte sich sonst ein zweiter an ihn, der danach nichts mehr findet.
            if (dao.countPendingDownloads() > 0) enqueueWorker(ExistingWorkPolicy.KEEP)
        }
    }

    /** Löscht die Downloads zu Ende gehörter Folgen, deren Frist abgelaufen ist. @return Anzahl. */
    suspend fun removeExpiredHeard(): Int =
        removeExpired(dao.observeFinishedDownloads().first(), settings.deleteHeardAfterDays.first(), loadedBookId.first())

    private suspend fun removeExpired(finished: List<FinishedDownloadRow>, days: Int, loaded: Long?): Int {
        // Die Datei ist fertig, wenn sie zuletzt geschrieben wurde (Umbenennen ändert die Zeit nicht).
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
