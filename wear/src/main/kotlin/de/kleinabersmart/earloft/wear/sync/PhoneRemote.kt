package de.kleinabersmart.earloft.wear.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearRemoteAction
import de.kleinabersmart.earloft.core.wearsync.WearRemoteChaptersDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteCommand
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteEpisodesResult
import de.kleinabersmart.earloft.core.wearsync.WearRemoteLibraryDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteListKind
import de.kleinabersmart.earloft.core.wearsync.WearRemotePlayRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteStateDto
import de.kleinabersmart.earloft.core.wearsync.WearRemoteSubscription
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTracksRequest
import de.kleinabersmart.earloft.core.wearsync.WearRemoteTracksResult
import de.kleinabersmart.earloft.core.wearsync.assetPayloadsAt
import de.kleinabersmart.earloft.core.wearsync.changesAt
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import de.kleinabersmart.earloft.core.wearsync.sendAndAwait
import de.kleinabersmart.earloft.core.wearsync.sendPayload
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

    /**
     * Cover des laufenden Albums bzw. Buchs als ([WearRemoteStateDto.coverKey], Bild); das Bild ist `null`, wenn das
     * Handy keines hat.
     */
    val cover: Flow<Pair<Long, Bitmap?>?> = dataClient.changesAt(WearPaths.REMOTE_COVER)
        .subscribed()
        .map {
            val (albumId, jpeg) = dataClient.payloadsAt(WearPaths.REMOTE_COVER).values.firstNotNullOfOrNull { WearCodec.decodeCover(it) }
                ?: return@map null
            albumId to withContext(Dispatchers.Default) { if (jpeg.isEmpty()) null else BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
        }
        .distinctUntilChanged { old, new -> old?.first == new?.first && (old?.second == null) == (new?.second == null) }

    /** Kapitel des Buchs bzw. der Folge, die das Handy spielt (oder zuletzt spielte); `null`, solange es keine geschrieben hat. */
    val chapters: Flow<WearRemoteChaptersDto?> = dataClient.changesAt(WearPaths.REMOTE_CHAPTERS)
        .subscribed()
        .map {
            dataClient.payloadsAt(WearPaths.REMOTE_CHAPTERS).values.firstOrNull()?.let { bytes ->
                try {
                    WearCodec.decodeCompressed<WearRemoteChaptersDto>(bytes)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Kapitel des Handys nicht lesbar", e)
                    null
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Kapitel des Handys nicht lesbar", e)
                    null
                }
            }
        }
        .distinctUntilChanged()

    /** Alben, Künstler, Playlists, Abos und Bücher des Handys; `null`, solange das Handy sie nicht geschrieben hat. */
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

    /**
     * Fragt die Folgen eines Abos beim Handy ab (Wear-Konzept 17). `null`, wenn das Handy nicht erreichbar ist oder nicht
     * rechtzeitig antwortet.
     */
    suspend fun episodes(feedId: String): WearRemoteEpisodesResult? {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return null
        val requestId = UUID.randomUUID().toString()
        val answer = messageClient.sendAndAwait(
            nodeId = nodeId,
            path = WearPaths.REMOTE_EPISODES_REQUEST,
            payload = WearCodec.encode(WearRemoteEpisodesRequest(requestId, feedId)),
            responsePath = WearPaths.REMOTE_EPISODES_RESULT,
            timeoutMs = TRACKS_TIMEOUT_MS,
        ) { decodeEpisodes(it)?.requestId == requestId } ?: run {
            Log.w(TAG, "Keine Folgen von $feedId")
            return null
        }
        return decodeEpisodes(answer)
    }

    private fun decodeEpisodes(bytes: ByteArray): WearRemoteEpisodesResult? = try {
        WearCodec.decodeCompressed<WearRemoteEpisodesResult>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Folgenliste nicht lesbar", e)
        null
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Folgenliste nicht lesbar", e)
        null
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
