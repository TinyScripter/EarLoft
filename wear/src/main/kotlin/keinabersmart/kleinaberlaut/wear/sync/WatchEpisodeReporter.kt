package keinabersmart.kleinaberlaut.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.dao.PodcastDao
import keinabersmart.kleinaberlaut.core.database.dao.StatsDao
import keinabersmart.kleinaberlaut.core.database.entity.ListeningStatEntity
import keinabersmart.kleinaberlaut.core.model.BookKind
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeListeningDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeProgressDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeReportDto
import keinabersmart.kleinaberlaut.core.wearsync.WearEpisodeStateDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.putPayload
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
 * Meldet dem Handy, was auf der Uhr an Folgen gehört wurde (Wear-Konzept 16.1): neuere Fortschritte und die seit der letzten
 * Meldung gehörte Zeit, als DataItem `/kal/episodes/<uuid>` (kommt auch an, wenn das Handy gerade fehlt). Während eine
 * Folge spielt, schreibt der Player alle 15 s; gemeldet wird erst nach einer Ruhepause, sonst weckte jede Meldung das Handy.
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
        val feedIds = feedIdsByUrl.values.toSet()

        val progress = bookDao.keyedProgress()
            .filter { it.serverId in feedIds && it.progress.updatedAt > reported.progressAt(it.serverId, it.remoteId) }
        val listening = statsDao.all()
            .filter { it.source == ListeningStatEntity.SOURCE_APP && it.kind == BookKind.EPISODE }
            .mapNotNull { stat ->
                val feedId = feedIdsByUrl[stat.groupKey] ?: return@mapNotNull null
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
                WearEpisodeStateDto(it.serverId, it.remoteId, WearEpisodeProgressDto(p.positionMs, p.durationMs, p.finished, p.speed, p.updatedAt))
            },
            listening = listening.map { it.second },
        )
        if (!dataClient.putPayload(WearPaths.episodes(packet.packetId), WearCodec.encode(packet))) return@withLock
        progress.forEach { reported.markProgress(it.serverId, it.remoteId, it.progress.updatedAt) }
        listening.forEach { (stat, _) -> reported.markListened(stat.groupKey, stat.itemKey, stat.listenedMs) }
        Log.d(TAG, "Folgen gemeldet: ${progress.size} Fortschritte, ${listening.size} Zeiten")
    }

    private companion object {
        const val TAG = "WatchEpisodeReporter"
        const val QUIET_MS = 30_000L
    }
}
