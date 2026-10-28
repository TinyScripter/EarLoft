package keinabersmart.kleinaberlaut.core.wearsync

import android.net.Uri
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await

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
suspend fun CapabilityClient.reachableNodes(capability: String): Set<Node> =
    runCatchingApi("Capability $capability abfragen") {
        getCapability(capability, CapabilityClient.FILTER_REACHABLE).await().nodes.toSet()
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
