package keinabersmart.kleinaberlaut.wear

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
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.wear.PhoneFileLocator
import keinabersmart.kleinaberlaut.core.data.wear.WatchPlay
import keinabersmart.kleinaberlaut.core.data.wear.WatchPlayImporter
import keinabersmart.kleinaberlaut.core.data.wear.WearSelectionRepository
import keinabersmart.kleinaberlaut.core.data.wear.WearServerMirror
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearPlaysDto
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectRequest
import keinabersmart.kleinaberlaut.core.wearsync.delete
import keinabersmart.kleinaberlaut.core.wearsync.payloadOrNull
import keinabersmart.kleinaberlaut.core.wearsync.sendFile
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject

/**
 * Nachrichten der Uhr ans Handy (Wear-Konzept 4). Die Play-Dienste starten den Dienst bei Bedarf, auch
 * wenn die App nicht läuft. Zugestellt wird nur zwischen Apps mit gleicher applicationId und Signatur.
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    @Inject lateinit var serverMirror: WearServerMirror
    @Inject lateinit var messageClient: MessageClient
    @Inject lateinit var channelClient: ChannelClient
    @Inject lateinit var fileLocator: PhoneFileLocator
    @Inject lateinit var dataClient: DataClient
    @Inject lateinit var playImporter: WatchPlayImporter
    @Inject lateinit var selections: WearSelectionRepository
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CREDENTIALS_REQUEST -> {
                val request = decodeOrNull<WearCredentialsRequest>(event) ?: return
                applicationScope.launch { sendPassword(event.sourceNodeId, request.sourceId) }
            }
            WearPaths.SELECT -> {
                val request = decodeOrNull<WearSelectRequest>(event) ?: return
                // Nur Favoriten und Playlists lassen sich von der Uhr aus auswählen (Wear-Konzept 5).
                if (request.kind == WearSelectionKind.ALBUM) return
                applicationScope.launch { selections.setSelected(request.kind, request.refId, request.selected) }
            }
            WearPaths.CLEAR_SELECTIONS -> applicationScope.launch { selections.clear() }
        }
    }

    /** Wiedergabe-Pakete der Uhr (Wear-Konzept 10): importieren, dann das DataItem löschen. */
    override fun onDataChanged(events: DataEventBuffer) {
        // Die Ereignisse nur hier lesen; der Puffer wird danach freigegeben.
        val packets = events.filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path?.startsWith(WearPaths.PLAYS_PREFIX) == true }
            .mapNotNull { event -> event.dataItem.payloadOrNull()?.let { event.dataItem.uri to it } }
        packets.forEach { (uri, payload) -> applicationScope.launch { importPlays(uri, payload) } }
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

    /**
     * Die Uhr holt eine Datei (`/kal/file/<sourceUid>`, Wear-Konzept 8.4): Das Handy schickt die lokale Datei oder
     * den fertigen Download im Original, ohne Datei nur die Kopfzeile „keine Datei“.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path
        if (!path.startsWith(WearPaths.FILE_CHANNEL_PREFIX)) return
        val sourceUid = path.removePrefix(WearPaths.FILE_CHANNEL_PREFIX)
        applicationScope.launch {
            val file = fileLocator.open(sourceUid)
            Log.d(TAG, "Datei $sourceUid für die Uhr: ${file?.length?.let { "$it Bytes" } ?: "keine Datei"}")
            channelClient.sendFile(channel, file?.length ?: 0, file?.input)
        }
    }

    /** Das Passwort geht nur als Message an genau die anfragende Uhr, nie als DataItem (Wear-Konzept 4). */
    private suspend fun sendPassword(nodeId: String, sourceId: String) {
        val password = serverMirror.password(sourceId)
        if (password == null) {
            Log.w(TAG, "Kein Passwort für $sourceId")
            return
        }
        messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS, WearCodec.encode(WearCredentialsDto(sourceId, password)))
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
