package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.net.toUri
import androidx.wear.remote.interactions.RemoteActivityHelper
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCapabilities
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.capabilityChanges
import de.kleinabersmart.earloft.core.wearsync.changesAt
import de.kleinabersmart.earloft.core.wearsync.connectedNodesOrEmpty
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import de.kleinabersmart.earloft.core.wearsync.reachableNodes
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

/** Connection to the phone from the watch's point of view. */
sealed interface PhoneStatus {
    data object Checking : PhoneStatus

    /** No phone connected (out of range, Bluetooth off, not paired). */
    data object NotConnected : PhoneStatus

    /** A phone is connected, but EarLoft is not installed there. */
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
     * Current state, re-evaluated on changes (capability, [WearPaths.PHONE_INFO]) and on
     * [refresh]. Whether a phone is connected at all is not reported by the API as an event – hence
     * [refresh] e.g. on `ON_RESUME`.
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
     * The phone app counts as present if a connected device has the capability or has written a
     * [WearPaths.PHONE_INFO]. The second path applies when capabilities do not
     * arrive (observed between emulator images with differently signed Play services).
     */
    private suspend fun resolve(): PhoneStatus {
        val connected = nodeClient.connectedNodesOrEmpty().filter { it.id != CLOUD_NODE_ID }
        val byCapability = capabilityClient.reachableNodes(WearCapabilities.PHONE_APP).map { it.id }
        val byPhoneInfo = dataClient.payloadsAt(WearPaths.PHONE_INFO).keys
        Log.d(TAG, "Connected: ${connected.map { it.displayName }}, capability: $byCapability, phone info: $byPhoneInfo")
        return phoneStatusOf(connected, withApp = byCapability.toSet() + byPhoneInfo)
    }

    /** The connected phone with the app, otherwise `null` (for file transfers, Wear concept 8.4). */
    suspend fun connectedPhoneNodeId(): String? = (resolve() as? PhoneStatus.Connected)?.nodeId

    /** Opens the app's Play Store page on the phone. @return `false` if that did not work. */
    suspend fun openStoreOnPhone(nodeId: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW)
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setData("market://details?id=${context.packageName}".toUri())
        return try {
            RemoteActivityHelper(context).startRemoteActivity(intent, nodeId).await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Play Store not opened on the phone", e)
            false
        }
    }

    private companion object {
        const val TAG = "PhoneConnection"
        const val CLOUD_NODE_ID = "cloud"
    }
}

/**
 * State from the connected devices and the device IDs on which the phone app was detected.
 * Only connected devices count (a DataItem may come from a phone disconnected long ago); with
 * several, a directly connected one (`isNearby`) is preferred.
 */
internal fun phoneStatusOf(connected: List<Node>, withApp: Set<String>): PhoneStatus {
    val preferred = compareBy<Node> { if (it.isNearby) 0 else 1 }
    connected.filter { it.id in withApp }.minWithOrNull(preferred)?.let { return PhoneStatus.Connected(it.id) }
    return connected.minWithOrNull(preferred)?.let { PhoneStatus.AppMissing(it.id) } ?: PhoneStatus.NotConnected
}
