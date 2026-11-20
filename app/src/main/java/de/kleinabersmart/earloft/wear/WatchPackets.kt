package de.kleinabersmart.earloft.wear

import android.net.Uri
import android.util.Log
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeImporter
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeListening
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeProgress
import de.kleinabersmart.earloft.core.data.wear.WatchPlay
import de.kleinabersmart.earloft.core.data.wear.WatchPlayImporter
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPlaysDto
import de.kleinabersmart.earloft.core.wearsync.delete
import de.kleinabersmart.earloft.core.wearsync.payloadsUnder
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Listening history and episode/book progress the watch leaves as DataItems (Wear concept 4): imported (each package once,
 * by its ID) and then deleted. Normally from [PhoneWearListenerService.onDataChanged]; [catchUp] at app start reads what is
 * still lying around because the event never arrived (app force-stopped, review G51).
 */
@Singleton
class WatchPackets @Inject constructor(
    private val dataClient: DataClient,
    private val playImporter: WatchPlayImporter,
    private val episodeImporter: WatchEpisodeImporter,
) {
    suspend fun catchUp() {
        val plays = dataClient.payloadsUnder(WearPaths.PLAYS_PREFIX)
        val episodes = dataClient.payloadsUnder(WearPaths.EPISODES_PREFIX)
        if (plays.isEmpty() && episodes.isEmpty()) return
        Log.i(TAG, "Catching up on ${plays.size} history and ${episodes.size} progress packages from the watch")
        plays.forEach { (uri, payload) -> importPlays(uri, payload) }
        episodes.forEach { (uri, payload) -> importEpisodes(uri, payload) }
    }

    suspend fun importPlays(uri: Uri, payload: ByteArray) {
        val packet = try {
            WearCodec.decode<WearPlaysDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "$uri not readable", e)
            // A broken package would otherwise stay around forever.
            dataClient.delete(uri)
            return
        }
        val imported = playImporter.import(packet.packetId, packet.plays.map { WatchPlay(it.songUid, it.playedAt, it.listenedMs) })
        Log.d(TAG, "Packet ${packet.packetId}: $imported of ${packet.plays.size} plays imported")
        dataClient.delete(uri)
    }

    suspend fun importEpisodes(uri: Uri, payload: ByteArray) {
        val report = try {
            WearCodec.decode<WearEpisodeReportDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "$uri not readable", e)
            dataClient.delete(uri)
            return
        }
        val applied = episodeImporter.import(
            packetId = report.packetId,
            progress = report.progress.map {
                val p = it.progress
                WatchEpisodeProgress(it.feedId, it.guid, p.positionMs, p.durationMs, p.finished, p.speed, p.updatedAt, p.skipSilence, p.volumeBoostDb)
            },
            listening = report.listening.map { WatchEpisodeListening(it.feedId, it.guid, it.listenedMs, it.at) },
        )
        Log.d(TAG, "Packet ${report.packetId} (episodes/books): $applied of ${report.progress.size} progress entries, ${report.listening.size} listening times")
        dataClient.delete(uri)
    }

    private companion object {
        const val TAG = "WatchPackets"
    }
}
