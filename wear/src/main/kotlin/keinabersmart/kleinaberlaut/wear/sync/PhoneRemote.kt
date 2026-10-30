package keinabersmart.kleinaberlaut.wear.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteAction
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteCommand
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteLibraryDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteListKind
import keinabersmart.kleinaberlaut.core.wearsync.WearRemotePlayRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteStateDto
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteSubscription
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTracksRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearRemoteTracksResult
import keinabersmart.kleinaberlaut.core.wearsync.assetPayloadsAt
import keinabersmart.kleinaberlaut.core.wearsync.changesAt
import keinabersmart.kleinaberlaut.core.wearsync.payloadsAt
import keinabersmart.kleinaberlaut.core.wearsync.sendAndAwait
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Das Handy als Fernbedienung (Wear-Konzept 15): was es spielt, was es hat, und Befehle an es. */
@Singleton
class PhoneRemote @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val phoneConnection: PhoneConnection,
) {
    /** Was das Handy spielt; `null`, solange es nichts gemeldet hat. */
    val state: Flow<WearRemoteStateDto?> = dataClient.changesAt(WearPaths.REMOTE_STATE)
        .subscribed()
        .map { dataClient.payloadsAt(WearPaths.REMOTE_STATE).values.firstOrNull()?.let(::decodeState) }
        .distinctUntilChanged()

    /** Cover des laufenden Albums als (Album-ID, Bild); das Bild ist `null`, wenn das Handy keines hat. */
    val cover: Flow<Pair<Long, Bitmap?>?> = dataClient.changesAt(WearPaths.REMOTE_COVER)
        .subscribed()
        .map {
            val (albumId, jpeg) = dataClient.payloadsAt(WearPaths.REMOTE_COVER).values.firstNotNullOfOrNull { WearCodec.decodeCover(it) }
                ?: return@map null
            albumId to withContext(Dispatchers.Default) { if (jpeg.isEmpty()) null else BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
        }
        .distinctUntilChanged { old, new -> old?.first == new?.first && (old?.second == null) == (new?.second == null) }

    /** Alben und Playlists des Handys; `null`, solange das Handy sie nicht geschrieben hat. */
    val library: Flow<WearRemoteLibraryDto?> = dataClient.changesAt(WearPaths.REMOTE_LIBRARY)
        .subscribed()
        .map {
            dataClient.assetPayloadsAt(WearPaths.REMOTE_LIBRARY).values.firstOrNull()?.let { bytes ->
                try {
                    WearCodec.decodeCompressed<WearRemoteLibraryDto>(bytes)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Bibliotheksindex nicht lesbar", e)
                    null
                }
            }
        }

    /**
     * Meldet die Uhr beim Handy an, solange [this] gesammelt wird. Das Handy schreibt Zustand, Cover und Bibliotheksindex nur
     * für angemeldete Uhren; so weckt nicht jeder Titelwechsel am Handy die Uhr-App. Die Anmeldung gilt
     * [WearRemoteSubscription.TTL_MS] und wird alle [WearRemoteSubscription.RENEW_MS] erneuert.
     */
    private fun <T> Flow<T>.subscribed(): Flow<T> = channelFlow {
        launch {
            while (true) {
                sendToPhone(WearPaths.REMOTE_SUBSCRIBE, ByteArray(0))
                delay(WearRemoteSubscription.RENEW_MS)
            }
        }
        collect { send(it) }
    }

    /** @return `false`, wenn kein Handy erreichbar war oder das Senden scheiterte. */
    suspend fun send(action: WearRemoteAction, value: Long = 0): Boolean =
        sendToPhone(WearPaths.REMOTE_COMMAND, WearCodec.encode(WearRemoteCommand(action, value)))

    suspend fun play(request: WearRemotePlayRequest): Boolean = sendToPhone(WearPaths.REMOTE_PLAY, WearCodec.encode(request))

    /**
     * Fragt die Titel einer Liste beim Handy ab. `null`, wenn das Handy nicht erreichbar ist oder nicht rechtzeitig
     * antwortet; sonst die Antwort (bei [WearRemoteTracksResult.found] = `false` existiert die Liste nicht mehr).
     */
    suspend fun tracks(kind: WearRemoteListKind, refId: Long?): WearRemoteTracksResult? {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return null
        val requestId = UUID.randomUUID().toString()
        val answer = messageClient.sendAndAwait(
            nodeId = nodeId,
            path = WearPaths.REMOTE_TRACKS_REQUEST,
            payload = WearCodec.encode(WearRemoteTracksRequest(requestId, kind, refId)),
            responsePath = WearPaths.REMOTE_TRACKS_RESULT,
            timeoutMs = TRACKS_TIMEOUT_MS,
        ) { decodeTracks(it)?.requestId == requestId } ?: run {
            Log.w(TAG, "Keine Titelliste von $kind/$refId")
            return null
        }
        return decodeTracks(answer)
    }

    private fun decodeTracks(bytes: ByteArray): WearRemoteTracksResult? = try {
        WearCodec.decodeCompressed<WearRemoteTracksResult>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Titelliste nicht lesbar", e)
        null
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Titelliste nicht lesbar", e)
        null
    }

    private suspend fun sendToPhone(path: String, payload: ByteArray): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, path, payload)
    }

    private fun decodeState(bytes: ByteArray): WearRemoteStateDto? = try {
        WearCodec.decode<WearRemoteStateDto>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Zustand des Handys nicht lesbar", e)
        null
    }

    private companion object {
        const val TAG = "PhoneRemote"
        const val TRACKS_TIMEOUT_MS = 10_000L
    }
}
