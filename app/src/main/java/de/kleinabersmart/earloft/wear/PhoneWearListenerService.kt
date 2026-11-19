package de.kleinabersmart.earloft.wear

import android.util.Log
import android.net.Uri
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.wear.PhoneFile
import de.kleinabersmart.earloft.core.data.wear.PhoneFileLocator
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeImporter
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeListening
import de.kleinabersmart.earloft.core.data.wear.WatchEpisodeProgress
import de.kleinabersmart.earloft.core.data.wear.WatchPlay
import de.kleinabersmart.earloft.core.data.wear.WatchPlayImporter
import de.kleinabersmart.earloft.core.data.wear.WatchSpoken
import de.kleinabersmart.earloft.core.data.wear.WearSelectionRepository
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.model.BookKind
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearClearRequest
import de.kleinabersmart.earloft.core.wearsync.WearClearScope
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearCoverFile
import de.kleinabersmart.earloft.core.wearsync.WearCredentialsDto
import de.kleinabersmart.earloft.core.wearsync.WearCredentialsRequest
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPlaysDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteCommand
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTracksRequest
import de.kleinabersmart.earloft.core.wearsync.WearSelectRequest
import de.kleinabersmart.earloft.core.wearsync.WearSelectSpokenRequest
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import de.kleinabersmart.earloft.core.wearsync.delete
import de.kleinabersmart.earloft.core.wearsync.payloadOrNull
import de.kleinabersmart.earloft.core.wearsync.sendFile
import de.kleinabersmart.earloft.core.wearsync.sendPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.ByteArrayInputStream
import javax.inject.Inject

/**
 * Messages from the watch to the phone (Wear concept 4). Play services start the service when needed, even
 * when the app isn't running. Delivery only happens between apps with the same applicationId and signature.
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    @Inject lateinit var serverMirror: WearServerMirror
    @Inject lateinit var audiobookshelf: AudiobookshelfServers
    @Inject lateinit var messageClient: MessageClient
    @Inject lateinit var channelClient: ChannelClient
    @Inject lateinit var fileLocator: PhoneFileLocator
    @Inject lateinit var dataClient: DataClient
    @Inject lateinit var playImporter: WatchPlayImporter
    @Inject lateinit var episodeImporter: WatchEpisodeImporter
    @Inject lateinit var selections: WearSelectionRepository
    @Inject lateinit var remote: WearRemoteHandler
    @Inject lateinit var remotePublisher: WearRemotePublisher
    @Inject lateinit var watchSpoken: WatchSpoken
    @Inject lateinit var thumbnails: WearThumbnails
    @Inject lateinit var transfers: WatchFileTransfers
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CREDENTIALS_REQUEST -> {
                val request = decodeOrNull<WearCredentialsRequest>(event) ?: return
                applicationScope.launch { sendPassword(event.sourceNodeId, request.sourceId) }
            }
            WearPaths.SELECT -> {
                val request = decodeOrNull<WearSelectRequest>(event) ?: return
                // Albums, playlists and favorites ("On the watch" switch, Wear concept 17).
                applicationScope.launch { selections.setSelected(request.kind, request.refId, request.selected) }
            }
            WearPaths.SELECT_SPOKEN -> {
                val request = decodeOrNull<WearSelectSpokenRequest>(event) ?: return
                applicationScope.launch {
                    val serverId = request.serverId
                    val remoteId = request.remoteId
                    if (serverId != null && remoteId != null) {
                        watchSpoken.setSelected(serverId, remoteId, request.selected)
                    } else {
                        watchSpoken.setSelected(request.bookId, request.selected)
                    }
                }
            }
            WearPaths.CLEAR_SELECTIONS -> {
                val scope = if (event.data.isEmpty()) WearClearScope.ALL else decodeOrNull<WearClearRequest>(event)?.scope ?: return
                applicationScope.launch {
                    if (scope == WearClearScope.ALL || scope == WearClearScope.MUSIC) selections.clear()
                    when (scope) {
                        WearClearScope.ALL -> watchSpoken.clear()
                        WearClearScope.PODCASTS -> watchSpoken.clear(BookKind.EPISODE)
                        WearClearScope.BOOKS -> watchSpoken.clear(BookKind.BOOK)
                        WearClearScope.MUSIC -> Unit
                    }
                }
            }
            WearPaths.REMOTE_SUBSCRIBE -> remotePublisher.subscribe(applicationScope)
            WearPaths.REMOTE_COMMAND -> {
                val command = decodeOrNull<WearRemoteCommand>(event) ?: return
                // Immediately on the message's thread: commands stay in order, only seeking within a book waits for the timeline.
                applicationScope.launch(start = CoroutineStart.UNDISPATCHED) { remote.command(command) }
            }
            WearPaths.REMOTE_PLAY -> {
                val request = decodeOrNull<WearRemotePlayRequest>(event) ?: return
                applicationScope.launch { remote.play(request) }
            }
            WearPaths.REMOTE_TRACKS_REQUEST -> {
                val request = decodeOrNull<WearRemoteTracksRequest>(event) ?: return
                applicationScope.launch { remote.sendTracks(event.sourceNodeId, request) }
            }
            WearPaths.REMOTE_EPISODES_REQUEST -> {
                val request = decodeOrNull<WearRemoteEpisodesRequest>(event) ?: return
                applicationScope.launch { remote.sendEpisodes(event.sourceNodeId, request) }
            }
        }
    }

    /**
     * Packages from the watch – plays (Wear concept 10) and played episodes and books (16.1/16.2): import, then delete the
     * DataItem. Plus [WearPaths.PHONE_WANTED]: the watch is waiting for the phone; the reply tells it that it is back (review V1).
     */
    override fun onDataChanged(events: DataEventBuffer) {
        // Read the events only here; the buffer is released afterwards.
        val packets = events.filter { it.type == DataEvent.TYPE_CHANGED }
            .mapNotNull { event -> event.dataItem.payloadOrNull()?.let { event.dataItem.uri to it } }
        packets.forEach { (uri, payload) ->
            val path = uri.path.orEmpty()
            when {
                path.startsWith(WearPaths.PLAYS_PREFIX) -> applicationScope.launch { importPlays(uri, payload) }
                path.startsWith(WearPaths.EPISODES_PREFIX) -> applicationScope.launch { importEpisodes(uri, payload) }
                path == WearPaths.PHONE_WANTED -> uri.host?.let { watchNode ->
                    Log.d(TAG, "Watch $watchNode is waiting for the phone, answering")
                    applicationScope.launch { messageClient.sendPayload(watchNode, WearPaths.PHONE_BACK, payload) }
                }
            }
        }
    }

    private suspend fun importPlays(uri: Uri, payload: ByteArray) {
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

    private suspend fun importEpisodes(uri: Uri, payload: ByteArray) {
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

    /**
     * The watch fetches a file (`/earloft/file/<sourceUid>`, Wear concept 8.4; episodes: [WearEpisodeFile], 16.1; book files:
     * [WearBookFile], 16.2; images: [WearCoverFile], thumbnails: [WearThumbnailFile], 17): the phone sends the local file or the finished download as the original,
     * without a file only the "no file" header. If the watch resumes (`/earloft/file-from/<offset>/…`), only the rest goes out.
     * Audio files run under [WatchFileTransfers] so the process isn't frozen in the meantime.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val (sourceUid, from) = WearPaths.parseFileChannel(channel.path) ?: return
        applicationScope.launch {
            val episode = WearEpisodeFile.parse(sourceUid)
            val bookTrack = WearBookFile.parse(sourceUid)
            val cover = WearCoverFile.parse(sourceUid)
            val thumbnail = WearThumbnailFile.parse(sourceUid)
            val file = when {
                episode != null -> fileLocator.openEpisode(episode.first, episode.second)
                bookTrack != null -> fileLocator.openBookTrack(bookTrack.serverId, bookTrack.remoteId, bookTrack.trackIndex)
                cover != null -> fileLocator.openCover(cover.first, cover.second)
                thumbnail != null -> thumbnails.open(thumbnail)?.let { PhoneFile(it.size.toLong(), ByteArrayInputStream(it)) }
                else -> fileLocator.open(sourceUid)
            }
            Log.d(TAG, "File $sourceUid for the watch: ${file?.length?.let { "$it bytes from $from" } ?: "no file"}")
            val send: suspend () -> Unit = { channelClient.sendFile(channel, file?.length ?: 0, file?.input, from) }
            // Images are small and come by the dozen when browsing lists; no work request for them.
            if (file != null && cover == null && thumbnail == null) transfers.track(send) else send()
        }
    }

    /**
     * The password (for Jellyfin user ID and token) or the API key of an Audiobookshelf server (16.2) is sent only
     * as a message to exactly the requesting watch, never as a DataItem (Wear concept 4).
     */
    private suspend fun sendPassword(nodeId: String, sourceId: String) {
        val password = serverMirror.password(sourceId) ?: audiobookshelf.apiKey(sourceId)
        if (password == null) {
            Log.w(TAG, "No password for $sourceId")
            return
        }
        val credentials = WearCredentialsDto(sourceId, password, serverMirror.deviceId(sourceId))
        messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS, WearCodec.encode(credentials))
    }

    private inline fun <reified T> decodeOrNull(event: MessageEvent): T? = try {
        WearCodec.decode<T>(event.data)
    } catch (e: SerializationException) {
        Log.w(TAG, "${event.path} not readable", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "${event.path} not readable", e)
        null
    }

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
