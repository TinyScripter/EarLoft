package de.kleinabersmart.kleinaberlaut.core.wearsync

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
 * Meldet sofort einmal und dann bei jeder Änderung von [capability]. Den aktuellen Stand liefert
 * [reachableNodes] – der Listener allein reicht nicht, weil er z. B. nach einem Neustart der
 * Play-Dienste nichts meldet.
 */
fun CapabilityClient.capabilityChanges(capability: String): Flow<Unit> = callbackFlow {
    val listener = CapabilityClient.OnCapabilityChangedListener { info ->
        Log.d(TAG, "Capability ${info.name} geändert: ${info.nodes.map { it.displayName }}")
        trySend(Unit)
    }
    // `await()` liefert hier `Void` (= null); deshalb ausdrücklich Unit für „registriert“.
    val registered = runCatchingApi("Capability $capability beobachten") { addListener(listener, capability).await(); Unit } != null
    send(Unit)
    awaitClose { if (registered) removeListener(listener, capability) }
}

/**
 * Erreichbare Geräte mit [capability]. Ohne Data Layer (z. B. Handy ohne Wear-OS-App, keine
 * Play-Dienste) leer, statt zu werfen.
 */
suspend fun CapabilityClient.reachableNodes(capability: String): Set<Node> = nodesWith(capability, CapabilityClient.FILTER_REACHABLE)

/** Alle gekoppelten Geräte mit [capability], auch wenn sie gerade nicht verbunden sind. */
suspend fun CapabilityClient.pairedNodes(capability: String): Set<Node> = nodesWith(capability, CapabilityClient.FILTER_ALL)

private suspend fun CapabilityClient.nodesWith(capability: String, filter: Int): Set<Node> =
    runCatchingApi("Capability $capability abfragen") {
        getCapability(capability, filter).await().nodes.toSet()
    }.orEmpty()

/**
 * Schreibt [payload] (siehe [WearCodec]) als DataItem unter [path]. Gleicher Inhalt löst bei der
 * Gegenstelle kein Ereignis aus. @return `false`, wenn der Data Layer nicht verfügbar ist.
 */
suspend fun DataClient.putPayload(path: String, payload: ByteArray, urgent: Boolean = true): Boolean {
    val request = PutDataMapRequest.create(path).apply {
        dataMap.putInt(WearDataKeys.PROTOCOL, WearProtocol.VERSION)
        dataMap.putByteArray(WearDataKeys.PAYLOAD, payload)
    }.asPutDataRequest()
    if (urgent) request.setUrgent()
    return runCatchingApi("$path schreiben") { putDataItem(request).await() } != null
}

/**
 * Wie [putPayload], aber als Asset (für Nutzlasten über der 100-KB-Grenze eines DataItems) und mit
 * [hash] des Inhalts, damit der Absender Unverändertes nicht neu schreibt (siehe [hashesAt]).
 */
suspend fun DataClient.putAssetPayload(path: String, payload: ByteArray, hash: String): Boolean {
    val request = PutDataMapRequest.create(path).apply {
        dataMap.putInt(WearDataKeys.PROTOCOL, WearProtocol.VERSION)
        dataMap.putAsset(WearDataKeys.PAYLOAD, Asset.createFromBytes(payload))
        dataMap.putString(WearDataKeys.HASH, hash)
    }.asPutDataRequest().setUrgent()
    return runCatchingApi("$path schreiben") { putDataItem(request).await() } != null
}

/** [WearDataKeys.HASH] der DataItems unter [path]: `nodeId → hash`. */
suspend fun DataClient.hashesAt(path: String): Map<String, String> =
    runCatchingApi("$path lesen") {
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
 * Asset-Nutzlasten (siehe [putAssetPayload]) unter [path]: `nodeId → payload`. Nutzlasten eines
 * anderen [WearProtocol.VERSION] werden übergangen.
 */
suspend fun DataClient.assetPayloadsAt(path: String): Map<String, ByteArray> {
    val assets = runCatchingApi("$path lesen") {
        val buffer = getDataItems(allNodesUri(path)).await()
        try {
            buffer.mapNotNull { item ->
                val dataMap = DataMapItem.fromDataItem(item).dataMap
                val nodeId = item.uri.host
                val asset = dataMap.getAsset(WearDataKeys.PAYLOAD)
                when {
                    nodeId == null || asset == null -> null
                    dataMap.getInt(WearDataKeys.PROTOCOL) != WearProtocol.VERSION -> {
                        Log.w(TAG, "$path von $nodeId: Protokoll ${dataMap.getInt(WearDataKeys.PROTOCOL)} wird nicht unterstützt")
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
        runCatchingApi("Asset $path lesen") {
            getFdForAsset(asset).await().inputStream.use { nodeId to it.readBytes() }
        }
    }.toMap()
}

/** Nutzlast und Protokollversion eines DataItems; `null`, wenn es keine Nutzlast hat. Nur im Ereignis-Callback aufrufen. */
fun DataItem.payloadOrNull(): ByteArray? {
    val dataMap = DataMapItem.fromDataItem(this).dataMap
    return dataMap.getByteArray(WearDataKeys.PAYLOAD)
        ?.takeIf { dataMap.getInt(WearDataKeys.PROTOCOL) == WearProtocol.VERSION }
}

/** Löscht das DataItem unter [uri] (z. B. ein importiertes Wiedergabe-Paket). @return `false` bei Fehler. */
suspend fun DataClient.delete(uri: Uri): Boolean =
    runCatchingApi("$uri löschen") { deleteDataItems(uri).await() } != null

/** Schickt eine Message; nur zugestellt, wenn [nodeId] gerade erreichbar ist. @return `false` bei Fehler. */
suspend fun MessageClient.sendPayload(nodeId: String, path: String, payload: ByteArray): Boolean =
    runCatchingApi("Message $path an $nodeId senden") { sendMessage(nodeId, path, payload).await() } != null

/**
 * Schickt [payload] an [nodeId] und wartet höchstens [timeoutMs] auf die erste Message unter [responsePath], deren Inhalt
 * [accept] annimmt (zur Zuordnung der Antwort). Der Listener steht vor dem Senden, die Antwort kann also nicht verloren gehen.
 * @return die Antwort; `null` bei Fehler oder Zeitüberschreitung.
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
    runCatchingApi("Antwort $responsePath erwarten") { addListener(listener).await(); Unit } ?: return null
    try {
        if (!sendPayload(nodeId, path, payload)) return null
        return withTimeoutOrNull(timeoutMs) { response.await() }
    } finally {
        removeListener(listener)
    }
}

/** Nutzlasten der DataItems unter [path] von allen Geräten (auch dem eigenen): `nodeId → payload`. */
suspend fun DataClient.payloadsAt(path: String): Map<String, ByteArray> =
    runCatchingApi("$path lesen") {
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

/** Meldet sofort einmal und dann bei jeder Änderung eines DataItems unter [path]. */
fun DataClient.changesAt(path: String): Flow<Unit> = callbackFlow {
    // Ereignisse nur innerhalb des Callbacks lesen; der Puffer wird danach freigegeben.
    val listener = DataClient.OnDataChangedListener { events ->
        if (events.any { it.dataItem.uri.path == path }) trySend(Unit)
    }
    val registered = runCatchingApi("$path beobachten") { addListener(listener).await(); Unit } != null
    send(Unit)
    awaitClose { if (registered) removeListener(listener) }
}

private fun allNodesUri(path: String): Uri =
    Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(path).build()

/** Alle derzeit verbundenen Geräte (unabhängig davon, ob dort unsere App läuft). */
suspend fun NodeClient.connectedNodesOrEmpty(): List<Node> =
    runCatchingApi("Verbundene Geräte abfragen") { connectedNodes.await() }.orEmpty()

/** Data-Layer-Fehler (`ApiException`, z. B. API nicht verfügbar) werden geloggt und als `null` gemeldet. */
internal suspend fun <T> runCatchingApi(what: String, block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: ApiException) {
    Log.w(TAG, "$what fehlgeschlagen (${e.statusCode})", e)
    null
}
