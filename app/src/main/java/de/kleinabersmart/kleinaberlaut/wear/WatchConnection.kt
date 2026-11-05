package de.kleinabersmart.kleinaberlaut.wear

import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearCapabilities
import de.kleinabersmart.kleinaberlaut.core.wearsync.WearPaths
import de.kleinabersmart.kleinaberlaut.core.wearsync.capabilityChanges
import de.kleinabersmart.kleinaberlaut.core.wearsync.changesAt
import de.kleinabersmart.kleinaberlaut.core.wearsync.pairedNodes
import de.kleinabersmart.kleinaberlaut.core.wearsync.payloadsAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import javax.inject.Inject
import javax.inject.Singleton

/** Ob eine Uhr mit kleinaberlaut gekoppelt ist (Wear-Konzept 5: Uhr-Funktionen nur dann zeigen). */
@Singleton
class WatchConnection @Inject constructor(
    private val capabilityClient: CapabilityClient,
    private val dataClient: DataClient,
) {
    /**
     * Gekoppelt genügt – die Auswahl gilt auch, wenn die Uhr gerade nicht verbunden ist. Erkannt wird die
     * Uhr-App an der Capability oder am DataItem [WearPaths.WATCH_INFO] (Capabilities kommen nicht überall
     * an, Wear-Konzept 4). Beim Deinstallieren der Uhr-App löschen die Play-Dienste das DataItem.
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
}
