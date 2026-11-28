package de.kleinabersmart.earloft.wear.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
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
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** The phone as a remote control (Wear concept 15): what it plays, what it has, and commands to it. */
@Singleton
class PhoneRemote @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val phoneConnection: PhoneConnection,
    @param:ApplicationScope private val applicationScope: CoroutineScope,
) {
    /**
     * The subscription at the phone, once for all observed flows ([subscribed]): runs as long as one is being collected, and
     * subscribes immediately with the first. A separate loop per flow would mean several messages per view every
     * [WearRemoteSubscription.RENEW_MS] (Review V20).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val subscription: Flow<Unit> = phoneConnection.status
        .map { it is PhoneStatus.Connected }
        .distinctUntilChanged()
        // Starts over when the phone is back: the subscription went out right away, not only with the next renewal up to 30 s later
        // (review5 P64).
        .transformLatest<Boolean, Unit> {
            while (true) {
                subscribe()
                delay(WearRemoteSubscription.RENEW_MS)
            }
        }
        .shareIn(applicationScope, SharingStarted.WhileSubscribed())

    /** When the last subscription reached the phone (`elapsedRealtime`); 0 = never. */
    @Volatile private var subscribedAt = 0L

    private val _awaitingReport = MutableStateFlow<Long?>(null)

    /**
     * The phone had forgotten the subscription ([WearRemoteSubscription.TTL_MS] without one) and rewrites its state now: until that
     * report comes, the last one may be minutes old and its position must not be counted on (review5 P64). Ends with a report sampled
     * after the new subscription, or after [AWAIT_REPORT_MS] at the latest.
     */
    val awaitingReport: Flow<Boolean> = _awaitingReport
        .transformLatest { since ->
            emit(since != null)
            if (since != null) {
                delay(AWAIT_REPORT_MS)
                _awaitingReport.compareAndSet(since, null)
            }
        }
        .distinctUntilChanged()

    private suspend fun subscribe() {
        val now = SystemClock.elapsedRealtime()
        val lapsed = now - subscribedAt > WearRemoteSubscription.TTL_MS
        val wallClock = System.currentTimeMillis()
        // Before sending: a quick report must find it to end it.
        if (lapsed) _awaitingReport.value = wallClock
        if (!sendToPhone(WearPaths.REMOTE_SUBSCRIBE, ByteArray(0))) {
            if (lapsed) _awaitingReport.compareAndSet(wallClock, null)
            return
        }
        subscribedAt = now
    }

    /** What the phone is playing; `null` as long as it has not reported anything. */
    val state: Flow<WearRemoteStateDto?> = dataClient.changesAt(WearPaths.REMOTE_STATE)
        .subscribed()
        .map { dataClient.payloadsAt(WearPaths.REMOTE_STATE).values.firstOrNull()?.let(::decodeState) }
        .onEach { dto ->
            // A report sampled after the subscription (with some slack between the clocks) is the rewritten one.
            val since = _awaitingReport.value
            if (since != null && dto != null && dto.sampledAt >= since - CLOCK_SLACK_MS) _awaitingReport.compareAndSet(since, null)
        }
        .distinctUntilChanged()

    /**
     * Cover of the current album or book as ([WearRemoteStateDto.coverKey], image); the image is `null` if the
     * phone has none.
     */
    val cover: Flow<Pair<Long, Bitmap?>?> = dataClient.changesAt(WearPaths.REMOTE_COVER)
        .subscribed()
        .map {
            val (albumId, jpeg) = dataClient.payloadsAt(WearPaths.REMOTE_COVER).values.firstNotNullOfOrNull { WearCodec.decodeCover(it) }
                ?: return@map null
            albumId to withContext(Dispatchers.Default) { if (jpeg.isEmpty()) null else BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) }
        }
        .distinctUntilChanged { old, new -> old?.first == new?.first && (old?.second == null) == (new?.second == null) }

    /** Chapters of the book or episode the phone is playing (or last played); `null` as long as it has not written any. */
    val chapters: Flow<WearRemoteChaptersDto?> = dataClient.changesAt(WearPaths.REMOTE_CHAPTERS)
        .subscribed()
        .map {
            dataClient.payloadsAt(WearPaths.REMOTE_CHAPTERS).values.firstOrNull()?.let { bytes ->
                try {
                    WearCodec.decodeCompressed<WearRemoteChaptersDto>(bytes)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Chapters from the phone not readable", e)
                    null
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Chapters from the phone not readable", e)
                    null
                }
            }
        }
        .distinctUntilChanged()

    /**
     * Albums, artists, playlists, subscriptions and books of the phone; `null` as long as the phone has not written them. Reading,
     * unpacking and decoding (a few hundred KB for large libraries) run off the main thread (Review V6).
     */
    val library: Flow<WearRemoteLibraryDto?> = dataClient.changesAt(WearPaths.REMOTE_LIBRARY)
        .subscribed()
        .map {
            dataClient.assetPayloadsAt(WearPaths.REMOTE_LIBRARY).values.firstOrNull()?.let { bytes ->
                try {
                    WearCodec.decodeCompressed<WearRemoteLibraryDto>(bytes)
                } catch (e: SerializationException) {
                    Log.w(TAG, "Library index not readable", e)
                    null
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "Library index not readable", e)
                    null
                }
            }
        }
        .flowOn(Dispatchers.Default)

    /**
     * Subscribes the watch at the phone as long as [this] is being collected. The phone writes state, cover and library index only
     * for subscribed watches; this way not every track change on the phone wakes the watch app. The subscription is valid for
     * [WearRemoteSubscription.TTL_MS] and is renewed every [WearRemoteSubscription.RENEW_MS].
     */
    private fun <T> Flow<T>.subscribed(): Flow<T> = channelFlow {
        launch { subscription.collect() }
        collect { send(it) }
    }

    /** @return `false` if no phone was reachable or sending failed. */
    suspend fun send(action: WearRemoteAction, value: Long = 0): Boolean =
        sendToPhone(WearPaths.REMOTE_COMMAND, WearCodec.encode(WearRemoteCommand(action, value)))

    suspend fun play(request: WearRemotePlayRequest): Boolean = sendToPhone(WearPaths.REMOTE_PLAY, WearCodec.encode(request))

    /**
     * Queries the tracks of a list from the phone. `null` if the phone is unreachable or does not answer in
     * time; otherwise the answer (with [WearRemoteTracksResult.found] = `false` the list no longer exists).
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
            Log.w(TAG, "No track list from $kind/$refId")
            return null
        }
        return decodeTracks(answer)
    }

    /**
     * Queries the episodes of a subscription from the phone (Wear concept 17). `null` if the phone is unreachable or does not
     * answer in time.
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
            Log.w(TAG, "No episodes from $feedId")
            return null
        }
        return decodeEpisodes(answer)
    }

    private fun decodeEpisodes(bytes: ByteArray): WearRemoteEpisodesResult? = try {
        WearCodec.decodeCompressed<WearRemoteEpisodesResult>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Episode list not readable", e)
        null
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Episode list not readable", e)
        null
    }

    private fun decodeTracks(bytes: ByteArray): WearRemoteTracksResult? = try {
        WearCodec.decodeCompressed<WearRemoteTracksResult>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Track list not readable", e)
        null
    } catch (e: java.io.IOException) {
        Log.w(TAG, "Track list not readable", e)
        null
    }

    private suspend fun sendToPhone(path: String, payload: ByteArray): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, path, payload)
    }

    private fun decodeState(bytes: ByteArray): WearRemoteStateDto? = try {
        WearCodec.decode<WearRemoteStateDto>(bytes)
    } catch (e: SerializationException) {
        Log.w(TAG, "Phone state not readable", e)
        null
    }

    private companion object {
        const val TAG = "PhoneRemote"
        const val TRACKS_TIMEOUT_MS = 10_000L
        const val AWAIT_REPORT_MS = 10_000L
        const val CLOCK_SLACK_MS = 5_000L
    }
}
