package de.kleinabersmart.tinyamp.wear

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
import de.kleinabersmart.tinyamp.core.data.di.ApplicationScope
import de.kleinabersmart.tinyamp.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.tinyamp.core.data.wear.PhoneFile
import de.kleinabersmart.tinyamp.core.data.wear.PhoneFileLocator
import de.kleinabersmart.tinyamp.core.data.wear.WatchEpisodeImporter
import de.kleinabersmart.tinyamp.core.data.wear.WatchEpisodeListening
import de.kleinabersmart.tinyamp.core.data.wear.WatchEpisodeProgress
import de.kleinabersmart.tinyamp.core.data.wear.WatchPlay
import de.kleinabersmart.tinyamp.core.data.wear.WatchPlayImporter
import de.kleinabersmart.tinyamp.core.data.wear.WatchSpoken
import de.kleinabersmart.tinyamp.core.data.wear.WearSelectionRepository
import de.kleinabersmart.tinyamp.core.data.wear.WearServerMirror
import de.kleinabersmart.tinyamp.core.model.BookKind
import de.kleinabersmart.tinyamp.core.wearsync.WearBookFile
import de.kleinabersmart.tinyamp.core.wearsync.WearClearRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearClearScope
import de.kleinabersmart.tinyamp.core.wearsync.WearCodec
import de.kleinabersmart.tinyamp.core.wearsync.WearCoverFile
import de.kleinabersmart.tinyamp.core.wearsync.WearCredentialsDto
import de.kleinabersmart.tinyamp.core.wearsync.WearCredentialsRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearEpisodeFile
import de.kleinabersmart.tinyamp.core.wearsync.WearEpisodeReportDto
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.WearPlaysDto
import de.kleinabersmart.tinyamp.core.wearsync.WearRemoteCommand
import de.kleinabersmart.tinyamp.core.wearsync.WearRemoteEpisodesRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearRemoteTracksRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearSelectRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearSelectSpokenRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearThumbnailFile
import de.kleinabersmart.tinyamp.core.wearsync.delete
import de.kleinabersmart.tinyamp.core.wearsync.payloadOrNull
import de.kleinabersmart.tinyamp.core.wearsync.sendFile
import de.kleinabersmart.tinyamp.core.wearsync.sendPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import java.io.ByteArrayInputStream
import javax.inject.Inject

/**
 * Nachrichten der Uhr ans Handy (Wear-Konzept 4). Die Play-Dienste starten den Dienst bei Bedarf, auch
 * wenn die App nicht läuft. Zugestellt wird nur zwischen Apps mit gleicher applicationId und Signatur.
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
                // Alben, Playlists und Favoriten (Schalter „Auf der Uhr“, Wear-Konzept 17).
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
                // Sofort im Thread der Nachricht: Befehle bleiben in ihrer Reihenfolge, nur das Spulen im Buch wartet auf die Zeitachse.
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

    /** Pakete der Uhr – Wiedergaben (Wear-Konzept 10) und gehörte Folgen und Bücher (16.1/16.2): importieren, dann das DataItem löschen. */
    override fun onDataChanged(events: DataEventBuffer) {
        // Die Ereignisse nur hier lesen; der Puffer wird danach freigegeben.
        val packets = events.filter { it.type == DataEvent.TYPE_CHANGED }
            .mapNotNull { event -> event.dataItem.payloadOrNull()?.let { event.dataItem.uri to it } }
        packets.forEach { (uri, payload) ->
            val path = uri.path.orEmpty()
            when {
                path.startsWith(WearPaths.PLAYS_PREFIX) -> applicationScope.launch { importPlays(uri, payload) }
                path.startsWith(WearPaths.EPISODES_PREFIX) -> applicationScope.launch { importEpisodes(uri, payload) }
            }
        }
    }

    private suspend fun importPlays(uri: Uri, payload: ByteArray) {
        val packet = try {
            WearCodec.decode<WearPlaysDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "$uri nicht lesbar", e)
            // Ein kaputtes Paket würde sonst für immer liegen bleiben.
            dataClient.delete(uri)
            return
        }
        val imported = playImporter.import(packet.packetId, packet.plays.map { WatchPlay(it.songUid, it.playedAt, it.listenedMs) })
        Log.d(TAG, "Paket ${packet.packetId}: $imported von ${packet.plays.size} Wiedergaben übernommen")
        dataClient.delete(uri)
    }

    private suspend fun importEpisodes(uri: Uri, payload: ByteArray) {
        val report = try {
            WearCodec.decode<WearEpisodeReportDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "$uri nicht lesbar", e)
            dataClient.delete(uri)
            return
        }
        val applied = episodeImporter.import(
            packetId = report.packetId,
            progress = report.progress.map {
                val p = it.progress
                WatchEpisodeProgress(it.feedId, it.guid, p.positionMs, p.durationMs, p.finished, p.speed, p.updatedAt)
            },
            listening = report.listening.map { WatchEpisodeListening(it.feedId, it.guid, it.listenedMs, it.at) },
        )
        Log.d(TAG, "Paket ${report.packetId} (Folgen/Bücher): $applied von ${report.progress.size} Fortschritten, ${report.listening.size} Zeiten")
        dataClient.delete(uri)
    }

    /**
     * Die Uhr holt eine Datei (`/tinyamp/file/<sourceUid>`, Wear-Konzept 8.4; Folgen: [WearEpisodeFile], 16.1; Buchdateien:
     * [WearBookFile], 16.2; Bilder: [WearCoverFile], Vorschaubilder: [WearThumbnailFile], 17): Das Handy schickt die lokale Datei oder den fertigen Download im Original,
     * ohne Datei nur die Kopfzeile „keine Datei“. Setzt die Uhr fort (`/tinyamp/file-from/<offset>/…`), geht nur der Rest hinaus.
     * Audiodateien laufen unter [WatchFileTransfers], damit der Prozess währenddessen nicht eingefroren wird.
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
            Log.d(TAG, "Datei $sourceUid für die Uhr: ${file?.length?.let { "$it Bytes ab $from" } ?: "keine Datei"}")
            val send: suspend () -> Unit = { channelClient.sendFile(channel, file?.length ?: 0, file?.input, from) }
            // Bilder sind klein und kommen beim Blättern in Listen gleich dutzendweise; dafür kein Auftrag.
            if (file != null && cover == null && thumbnail == null) transfers.track(send) else send()
        }
    }

    /**
     * Das Passwort (bei Jellyfin Benutzer-ID und Token) bzw. der API-Schlüssel eines Audiobookshelf-Servers (16.2) geht nur
     * als Message an genau die anfragende Uhr, nie als DataItem (Wear-Konzept 4).
     */
    private suspend fun sendPassword(nodeId: String, sourceId: String) {
        val password = serverMirror.password(sourceId) ?: audiobookshelf.apiKey(sourceId)
        if (password == null) {
            Log.w(TAG, "Kein Passwort für $sourceId")
            return
        }
        val credentials = WearCredentialsDto(sourceId, password, serverMirror.deviceId(sourceId))
        messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS, WearCodec.encode(credentials))
    }

    private inline fun <reified T> decodeOrNull(event: MessageEvent): T? = try {
        WearCodec.decode<T>(event.data)
    } catch (e: SerializationException) {
        Log.w(TAG, "${event.path} nicht lesbar", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "${event.path} nicht lesbar", e)
        null
    }

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
