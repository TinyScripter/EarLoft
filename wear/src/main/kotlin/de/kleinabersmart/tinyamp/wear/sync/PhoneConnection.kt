package de.kleinabersmart.tinyamp.wear.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.tinyamp.core.wearsync.WearCapabilities
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.capabilityChanges
import de.kleinabersmart.tinyamp.core.wearsync.changesAt
import de.kleinabersmart.tinyamp.core.wearsync.connectedNodesOrEmpty
import de.kleinabersmart.tinyamp.core.wearsync.payloadsAt
import de.kleinabersmart.tinyamp.core.wearsync.reachableNodes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.guava.await
import javax.inject.Inject
import javax.inject.Singleton

/** Verbindung zum Handy aus Sicht der Uhr. */
sealed interface PhoneStatus {
    data object Checking : PhoneStatus

    /** Kein Handy verbunden (außer Reichweite, Bluetooth aus, nicht gekoppelt). */
    data object NotConnected : PhoneStatus

    /** Ein Handy ist verbunden, aber dort ist TinyAmp nicht installiert. */
    data class AppMissing(val nodeId: String) : PhoneStatus

    data class Connected(val nodeId: String) : PhoneStatus
}

@Singleton
class PhoneConnection @Inject constructor(
    @ApplicationContext private val context: Context,
    private val capabilityClient: CapabilityClient,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
) {
    private val refreshRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /**
     * Aktueller Zustand, neu ermittelt bei Änderungen (Capability, [WearPaths.PHONE_INFO]) und bei
     * [refresh]. Ob überhaupt ein Handy verbunden ist, meldet die API nicht als Ereignis – daher
     * [refresh] z. B. bei `ON_RESUME`.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<PhoneStatus> =
        merge(
            capabilityClient.capabilityChanges(WearCapabilities.PHONE_APP),
            dataClient.changesAt(WearPaths.PHONE_INFO),
            refreshRequests,
        )
            .mapLatest { resolve() }
            .distinctUntilChanged()

    fun refresh() {
        refreshRequests.tryEmit(Unit)
    }

    /**
     * Die Handy-App gilt als vorhanden, wenn ein verbundenes Gerät die Capability hat oder ein
     * [WearPaths.PHONE_INFO] geschrieben hat. Der zweite Weg greift, wenn Capabilities nicht
     * ankommen (beobachtet zwischen Emulator-Images mit unterschiedlich signierten Play-Diensten).
     */
    private suspend fun resolve(): PhoneStatus {
        val connected = nodeClient.connectedNodesOrEmpty().filter { it.id != CLOUD_NODE_ID }
        val byCapability = capabilityClient.reachableNodes(WearCapabilities.PHONE_APP).map { it.id }
        val byPhoneInfo = dataClient.payloadsAt(WearPaths.PHONE_INFO).keys
        Log.d(TAG, "Verbunden: ${connected.map { it.displayName }}, Capability: $byCapability, Handy-Info: $byPhoneInfo")
        return phoneStatusOf(connected, withApp = byCapability.toSet() + byPhoneInfo)
    }

    /** Das verbundene Handy mit der App, sonst `null` (für Datei-Übertragungen, Wear-Konzept 8.4). */
    suspend fun connectedPhoneNodeId(): String? = (resolve() as? PhoneStatus.Connected)?.nodeId

    /** Öffnet die Play-Store-Seite der App auf dem Handy. @return `false`, wenn das nicht geklappt hat. */
    suspend fun openStoreOnPhone(nodeId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData(Uri.parse("market://details?id=${context.packageName}"))
        return try {
            RemoteActivityHelper(context).startRemoteActivity(intent, nodeId).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Play Store auf dem Handy nicht geöffnet", e)
            false
        }
    }

    private companion object {
        const val TAG = "PhoneConnection"
        const val CLOUD_NODE_ID = "cloud"
    }
}

/**
 * Zustand aus den verbundenen Geräten und den Geräte-IDs, auf denen die Handy-App erkannt wurde.
 * Nur verbundene Geräte zählen (ein DataItem kann von einem längst getrennten Handy stammen); bei
 * mehreren wird ein direkt verbundenes (`isNearby`) bevorzugt.
 */
internal fun phoneStatusOf(connected: List<Node>, withApp: Set<String>): PhoneStatus {
    val preferred = compareBy<Node> { if (it.isNearby) 0 else 1 }
    connected.filter { it.id in withApp }.minWithOrNull(preferred)?.let { return PhoneStatus.Connected(it.id) }
    return connected.minWithOrNull(preferred)?.let { PhoneStatus.AppMissing(it.id) } ?: PhoneStatus.NotConnected
}
