package de.kleinabersmart.earloft.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.data.spoken.BookProgressSync
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reports to the phone what was listened to on the watch in episodes and books (Wear concept 16.1/16.2): newer progress and the
 * time listened since the last report, as DataItem `/earloft/episodes/<uuid>` (arrives even if the phone is currently missing).
 * While something is playing, the player writes every 15 s; reporting only happens after a quiet period, otherwise every report would wake
 * the phone. When the player saves the position (pause, track change, stop), it goes out after [HELD_SETTLE_MS] so that you
 * can continue listening on the phone right away. For books the key is `serverId`/`remoteId` instead of `feedId`/guid.
 */
@Singleton
class WatchEpisodeReporter @Inject constructor(
    private val dataClient: DataClient,
    private val bookDao: BookDao,
    private val podcastDao: PodcastDao,
    private val statsDao: StatsDao,
    private val reported: ReportedEpisodes,
    private val progressSync: BookProgressSync,
) {
    private val mutex = Mutex()

    @OptIn(FlowPreview::class)
    fun start(scope: CoroutineScope) {
        scope.launch {
            // Once at startup: catches up on whatever did not go out last time (process ended). The quiet period
            // remains in effect after a pause too: it takes the listening time along in case the statistics wrote only after the report.
            merge(
                bookDao.observeAllProgress().debounce(QUIET_MS).map { },
                progressSync.held.debounce(HELD_SETTLE_MS).map { },
            ).onStart { emit(Unit) }.collect {
                // One failed report must not end the reports to the phone until the next start (review2 J92).
                try {
                    report()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Progress not sent to the phone", e)
                }
            }
        }
    }

    suspend fun report() = mutex.withLock {
        val feeds = podcastDao.feeds()
        val feedIdsByUrl = feeds.associate { it.url to it.feedId }

        // The watch only has mirrored items: every episode and every book comes from the phone.
        val progress = bookDao.keyedProgress()
            .filter { it.progress.updatedAt > reported.progressAt(it.serverId, it.remoteId) }
        // Only entries listened to since the last report (with slack for the clock): the delta is per entry anyway (review2 J95).
        val checkedAt = System.currentTimeMillis()
        val listening = statsDao.listenedSince(ListeningStatEntity.SOURCE_APP, reported.listeningCheckedAt() - CLOCK_SLACK_MS)
            .mapNotNull { stat ->
                // Episodes count under the feed address, books under their `serverId` (StatsRepository).
                val feedId = when (stat.kind) {
                    BookKind.EPISODE -> feedIdsByUrl[stat.groupKey]
                    BookKind.BOOK -> stat.groupKey
                } ?: return@mapNotNull null
                val delta = stat.listenedMs - reported.listenedMs(stat.groupKey, stat.itemKey)
                // Smaller than reported: the watch's statistics were cleared; start counting anew from here.
                if (delta < 0) reported.markListened(stat.groupKey, stat.itemKey, stat.listenedMs)
                if (delta <= 0) null else stat to WearEpisodeListeningDto(feedId, stat.itemKey, delta, stat.lastListenedAt)
            }
        if (progress.isEmpty() && listening.isEmpty()) {
            reported.markReported(emptyList(), emptyList(), checkedAt)
            return@withLock
        }

        val packet = WearEpisodeReportDto(
            packetId = UUID.randomUUID().toString(),
            progress = progress.map {
                val p = it.progress
                WearEpisodeStateDto(it.serverId, it.remoteId, WearEpisodeProgressDto(p.positionMs, p.durationMs, p.finished, p.speed, p.updatedAt, p.skipSilence, p.volumeBoostDb))
            },
            listening = listening.map { it.second },
        )
        if (!dataClient.putPayload(WearPaths.episodes(packet.packetId), WearCodec.encode(packet))) return@withLock
        reported.markReported(
            progress.map { Triple(it.serverId, it.remoteId, it.progress.updatedAt) },
            listening.map { (stat, _) -> Triple(stat.groupKey, stat.itemKey, stat.listenedMs) },
            checkedAt,
        )
        Log.d(TAG, "Episodes and books reported: ${progress.size} progress entries, ${listening.size} listening times")
    }

    private companion object {
        const val TAG = "WatchEpisodeReporter"
        const val QUIET_MS = 30_000L
        const val CLOCK_SLACK_MS = 60 * 60_000L

        /** Wait briefly until the player has also booked the listened time on pause; it goes out in the same package. */
        const val HELD_SETTLE_MS = 1_000L
    }
}
