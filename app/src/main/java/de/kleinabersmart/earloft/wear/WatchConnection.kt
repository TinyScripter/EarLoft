package de.kleinabersmart.earloft.wear

import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.NodeClient
import de.kleinabersmart.earloft.core.wearsync.WearCapabilities
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.capabilityChanges
import de.kleinabersmart.earloft.core.wearsync.changesAt
import de.kleinabersmart.earloft.core.wearsync.connectedNodesOrEmpty
import de.kleinabersmart.earloft.core.wearsync.pairedNodes
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/** Whether a watch with EarLoft is paired (Wear concept 5: show watch features only then). */
@Singleton
class WatchConnection @Inject constructor(
    private val capabilityClient: CapabilityClient,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
) {
    /**
     * Paired is enough – the selection also applies if the watch isn't currently connected. The watch app
     * is recognized by the capability or by the DataItem [WearPaths.WATCH_INFO] (capabilities don't arrive
     * everywhere, Wear concept 4). When the watch app is uninstalled, Play services delete the DataItem.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val watchAppInstalled: Flow<Boolean> =
        merge(
            capabilityClient.capabilityChanges(WearCapabilities.WATCH_APP),
            dataClient.changesAt(WearPaths.WATCH_INFO),
        )
            .mapLatest {
                capabilityClient.pairedNodes(WearCapabilities.WATCH_APP).isNotEmpty() ||
                    dataClient.payloadsAt(WearPaths.WATCH_INFO).isNotEmpty()
            }
            .distinctUntilChanged()

    /**
     * For the setup wizard (concept 15): EarLoft on the watch, a connected watch without EarLoft, or no watch.
     * Without the watch app only a currently connected watch counts; the Data Layer doesn't know paired but disconnected ones.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val setup: Flow<WatchSetup> = watchAppInstalled.mapLatest { installed ->
        when {
            installed -> WatchSetup.APP_INSTALLED
            nodeClient.connectedNodesOrEmpty().isNotEmpty() -> WatchSetup.APP_MISSING
            else -> WatchSetup.NONE
        }
    }.onEach { lastSetup = it }

    /**
     * What [setup] last returned, `null` before the first answer: each page of the wizard asks again, but starts with the
     * known state instead of "no watch" (otherwise the number of steps would jump, review D27).
     */
    @Volatile
    var lastSetup: WatchSetup? = null
        private set
}

enum class WatchSetup { NONE, APP_MISSING, APP_INSTALLED }
