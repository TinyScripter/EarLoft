package de.kleinabersmart.earloft.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.dao.StatsDao
import de.kleinabersmart.earloft.core.database.entity.ListeningStatEntity
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeListeningDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeProgressDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeStateDto
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.putPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meldet dem Handy, was auf der Uhr an Folgen und Büchern gehört wurde (Wear-Konzept 16.1/16.2): neuere Fortschritte und die
 * seit der letzten Meldung gehörte Zeit, als DataItem `/earloft/episodes/<uuid>` (kommt auch an, wenn das Handy gerade fehlt).
 * Während etwas spielt, schreibt der Player alle 15 s; gemeldet wird erst nach einer Ruhepause, sonst weckte jede Meldung
 * das Handy. Bei Büchern ist der Schlüssel `serverId`/`remoteId` statt `feedId`/guid.
 */
@Singleton
class WatchEpisodeReporter @Inject constructor(
    private val dataClient: DataClient,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val statsDao: StatsDao,
    private val reported: ReportedEpisodes,
) {
    private val mutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Beim Start einmal: Was beim letzten Mal nicht mehr hinausging (Prozess beendet), holt das nach.
            bookDao.observeAllProgress().debounce(QUIET_MS).onStart { emit(emptyList()) }.collect { report() }
        }
    }

    suspend fun report() = mutex.withLock {
        val feeds = podcastDao.feeds()
        val feedIdsByUrl = feeds.associate { it.url to it.feedId }

        // Auf der Uhr gibt es nur Gespiegeltes: jede Folge und jedes Buch stammt vom Handy.
        val progress = bookDao.keyedProgress()
            .filter { it.progress.updatedAt > reported.progressAt(it.serverId, it.remoteId) }
        val listening = statsDao.all()
            .filter { it.source == ListeningStatEntity.SOURCE_APP }
            .mapNotNull { stat ->
                // Folgen zählen unter der Adresse des Feeds, Bücher unter ihrer `serverId` (StatsRepository).
                val feedId = when (stat.kind) {
                    BookKind.EPISODE -> feedIdsByUrl[stat.groupKey]
                    BookKind.BOOK -> stat.groupKey
                } ?: return@mapNotNull null
                val delta = stat.listenedMs - reported.listenedMs(stat.groupKey, stat.itemKey)
                // Kleiner als gemeldet: Die Statistik der Uhr wurde geleert; ab hier neu zählen.
                if (delta < 0) reported.markListened(stat.groupKey, stat.itemKey, stat.listenedMs)
                if (delta <= 0) null else stat to WearEpisodeListeningDto(feedId, stat.itemKey, delta, stat.lastListenedAt)
            }
        if (progress.isEmpty() && listening.isEmpty()) return@withLock

        val packet = WearEpisodeReportDto(
            packetId = UUID.randomUUID().toString(),
            progress = progress.map {
                val p = it.progress
                WearEpisodeStateDto(it.serverId, it.remoteId, WearEpisodeProgressDto(p.positionMs, p.durationMs, p.finished, p.speed, p.updatedAt, p.skipSilence, p.volumeBoostDb))
            },
            listening = listening.map { it.second },
        )
        if (!dataClient.putPayload(WearPaths.episodes(packet.packetId), WearCodec.encode(packet))) return@withLock
        progress.forEach { reported.markProgress(it.serverId, it.remoteId, it.progress.updatedAt) }
        listening.forEach { (stat, _) -> reported.markListened(stat.groupKey, stat.itemKey, stat.listenedMs) }
        Log.d(TAG, "Folgen und Bücher gemeldet: ${progress.size} Fortschritte, ${listening.size} Zeiten")
    }

    private companion object {
        const val TAG = "WatchEpisodeReporter"
        const val QUIET_MS = 30_000L
    }
}
