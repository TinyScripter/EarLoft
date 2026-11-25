package de.kleinabersmart.earloft.core.wearsync

import android.net.Uri
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "WearDataLayer"

/**
 * Emits once immediately and then on every change of [capability]. The current state comes from
 * [reachableNodes] – the listener alone isn't enough because, e.g. after a restart of
 * Play services, it reports nothing.
 */
fun CapabilityClient.capabilityChanges(capability: String): Flow<Unit> = callbackFlow {
    val listener = CapabilityClient.OnCapabilityChangedListener { info ->
        Log.d(TAG, "Capability ${info.name} changed: ${info.nodes.map { it.displayName }}")
        trySend(Unit)
    }
    // `await()` returns `Void` (= null) here; hence explicitly Unit for "registered".
    val registered = runCatchingApi("Observe capability $capability") { addListener(listener, capability).await(); Unit } != null
    send(Unit)
    awaitClose { if (registered) removeListener(listener, capability) }
}

/**
 * Reachable devices with [capability]. Without a Data Layer (e.g. phone without the Wear OS app, no
 * Play services) empty instead of throwing.
 */
suspend fun CapabilityClient.reachableNodes(capability: String): Set<Node> = nodesWith(capability, CapabilityClient.FILTER_REACHABLE)

/** All paired devices with [capability], even if they aren't currently connected. */
suspend fun CapabilityClient.pairedNodes(capability: String): Set<Node> = nodesWith(capability, CapabilityClient.FILTER_ALL)

private suspend fun CapabilityClient.nodesWith(capability: String, filter: Int): Set<Node> =
    runCatchingApi("Query capability $capability") {
        getCapability(capability, filter).await().nodes.toSet()
    }.orEmpty()

/**
 * Writes [payload] (see [WearCodec]) as a DataItem under [path]. Identical content triggers no event on the
 * other side. @return `false` if the Data Layer is not available.
 */
suspend fun DataClient.putPayload(path: String, payload: ByteArray, urgent: Boolean = true): Boolean {
    val request = PutDataMapRequest.create(path).apply {
        dataMap.putInt(WearDataKeys.PROTOCOL, WearProtocol.VERSION)
        dataMap.putByteArray(WearDataKeys.PAYLOAD, payload)
    }.asPutDataRequest()
    if (urgent) request.setUrgent()
    return runCatchingApi("Write $path") { putDataItem(request).await() } != null
}

/**
 * Like [putPayload], but as an asset (for payloads above a DataItem's 100 KB limit) and with
 * [hash] of the content, so the sender doesn't rewrite unchanged content (see [hashesAt]).
 */
suspend fun DataClient.putAssetPayload(path: String, payload: ByteArray, hash: String): Boolean {
    val request = PutDataMapRequest.create(path).apply {
        dataMap.putInt(WearDataKeys.PROTOCOL, WearProtocol.VERSION)
        dataMap.putAsset(WearDataKeys.PAYLOAD, Asset.createFromBytes(payload))
        dataMap.putString(WearDataKeys.HASH, hash)
    }.asPutDataRequest().setUrgent()
    return runCatchingApi("Write $path") { putDataItem(request).await() } != null
}

/** [WearDataKeys.HASH] of the DataItems under [path]: `nodeId → hash`. */
suspend fun DataClient.hashesAt(path: String): Map<String, String> =
    runCatchingApi("Read $path") {
        val buffer = getDataItems(allNodesUri(path)).await()
        try {
            buffer.mapNotNull { item ->
                val hash = DataMapItem.fromDataItem(item).dataMap.getString(WearDataKeys.HASH)
                val nodeId = item.uri.host
                if (hash == null || nodeId == null) null else nodeId to hash
            }.toMap()
        } finally {
            buffer.release()
        }
    }.orEmpty()

/**
 * Asset payloads (see [putAssetPayload]) under [path]: `nodeId → payload`. Payloads of a
 * different [WearProtocol.VERSION] are skipped.
 */
suspend fun DataClient.assetPayloadsAt(path: String): Map<String, ByteArray> {
    val assets = runCatchingApi("Read $path") {
        val buffer = getDataItems(allNodesUri(path)).await()
        try {
            buffer.mapNotNull { item ->
                val dataMap = DataMapItem.fromDataItem(item).dataMap
                val nodeId = item.uri.host
                val asset = dataMap.getAsset(WearDataKeys.PAYLOAD)
                when {
                    nodeId == null || asset == null -> null
                    dataMap.getInt(WearDataKeys.PROTOCOL) != WearProtocol.VERSION -> {
                        Log.w(TAG, "$path from $nodeId: protocol ${dataMap.getInt(WearDataKeys.PROTOCOL)} is not supported")
                        null
                    }
                    else -> nodeId to asset
                }
            }
        } finally {
            buffer.release()
        }
    }.orEmpty()
    return assets.mapNotNull { (nodeId, asset) ->
        runCatchingApi("Read asset $path") {
            getFdForAsset(asset).await().inputStream.use { nodeId to it.readBytes() }
        }
    }.toMap()
}

/** Payload and protocol version of a DataItem; `null` if it has no payload. Only call in the event callback. */
fun DataItem.payloadOrNull(): ByteArray? {
    val dataMap = DataMapItem.fromDataItem(this).dataMap
    return dataMap.getByteArray(WearDataKeys.PAYLOAD)
        ?.takeIf { dataMap.getInt(WearDataKeys.PROTOCOL) == WearProtocol.VERSION }
}

/** Deletes the DataItem under [uri] (e.g. an imported playback package). @return `false` on error. */
suspend fun DataClient.delete(uri: Uri): Boolean =
    runCatchingApi("Delete $uri") { deleteDataItems(uri).await() } != null

/**
 * Whether the DataItem under [uri] still exists (another path may have imported and deleted it meanwhile). On a Data Layer error
 * `true`: better to look at an item twice than to leave one behind.
 */
suspend fun DataClient.exists(uri: Uri): Boolean =
    runCatchingApi("Look up $uri") { getDataItem(uri).await() != null } ?: true

/** Sends a message; only delivered if [nodeId] is currently reachable. @return `false` on error. */
suspend fun MessageClient.sendPayload(nodeId: String, path: String, payload: ByteArray): Boolean =
    runCatchingApi("Send message $path to $nodeId") { sendMessage(nodeId, path, payload).await() } != null

/**
 * Sends [payload] to [nodeId] and waits at most [timeoutMs] for the first message under [responsePath] whose content
 * [accept] accepts (to match the response). The listener is set up before sending, so the response can't get lost.
 * @return the response; `null` on error or timeout.
 */
suspend fun MessageClient.sendAndAwait(
    nodeId: String,
    path: String,
    payload: ByteArray,
    responsePath: String,
    timeoutMs: Long,
    accept: (ByteArray) -> Boolean,
): ByteArray? {
    val response = CompletableDeferred<ByteArray>()
    val listener = MessageClient.OnMessageReceivedListener { event ->
        if (event.path == responsePath && accept(event.data)) response.complete(event.data)
    }
    runCatchingApi("Await response $responsePath") { addListener(listener).await(); Unit } ?: return null
    try {
        if (!sendPayload(nodeId, path, payload)) return null
        return withTimeoutOrNull(timeoutMs) { response.await() }
    } finally {
        removeListener(listener)
    }
}

/** Payloads of the DataItems under [path] from all devices (including our own): `nodeId → payload`. */
suspend fun DataClient.payloadsAt(path: String): Map<String, ByteArray> =
    runCatchingApi("Read $path") {
        val buffer = getDataItems(allNodesUri(path)).await()
        try {
            buffer.mapNotNull { item ->
                val payload = DataMapItem.fromDataItem(item).dataMap.getByteArray(WearDataKeys.PAYLOAD)
                val nodeId = item.uri.host
                if (payload == null || nodeId == null) null else nodeId to payload
            }.toMap()
        } finally {
            buffer.release()
        }
    }.orEmpty()

/**
 * DataItems of all devices whose path starts with [prefix], with their address: `uri → payload` (only those of this protocol
 * version). For packages whose change event never arrived.
 */
suspend fun DataClient.payloadsUnder(prefix: String): Map<Uri, ByteArray> =
    runCatchingApi("Read $prefix…") {
        val buffer = getDataItems(allNodesUri(prefix), DataClient.FILTER_PREFIX).await()
        try {
            buffer.mapNotNull { item -> item.payloadOrNull()?.let { item.uri to it } }.toMap()
        } finally {
            buffer.release()
        }
    }.orEmpty()

/** Emits once immediately and then on every change of a DataItem under [path]. */
fun DataClient.changesAt(path: String): Flow<Unit> = callbackFlow {
    // Only read events within the callback; the buffer is released afterwards.
    val listener = DataClient.OnDataChangedListener { events ->
        if (events.any { it.dataItem.uri.path == path }) trySend(Unit)
    }
    val registered = runCatchingApi("Observe $path") { addListener(listener).await(); Unit } != null
    send(Unit)
    awaitClose { if (registered) removeListener(listener) }
}

private fun allNodesUri(path: String): Uri =
    Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(path).build()

/** All currently connected devices (regardless of whether our app runs there). */
suspend fun NodeClient.connectedNodesOrEmpty(): List<Node> =
    runCatchingApi("Query connected devices") { connectedNodes.await() }.orEmpty()

/** Deletes our own DataItem under [path] (DataItems belong to the device that wrote them). @return `false` on error. */
suspend fun DataClient.deleteOwn(nodeClient: NodeClient, path: String): Boolean {
    val localId = runCatchingApi("Query own device") { nodeClient.localNode.await().id } ?: return false
    return delete(Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority(localId).path(path).build())
}

/** Data Layer errors (`ApiException`, e.g. API not available) are logged and reported as `null`. */
internal suspend fun <T> runCatchingApi(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    Log.w(TAG, "$what failed (${e.statusCode})", e)
    null
}
