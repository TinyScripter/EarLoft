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

/** Ob eine Uhr mit EarLoft gekoppelt ist (Wear-Konzept 5: Uhr-Funktionen nur dann zeigen). */
@Singleton
class WatchConnection @Inject constructor(
    private val capabilityClient: CapabilityClient,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
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

    /**
     * Für den Einrichtungsassistenten (Konzept 15): EarLoft auf der Uhr, eine verbundene Uhr ohne EarLoft oder keine Uhr.
     * Ohne Uhr-App zählt nur eine gerade verbundene Uhr; gekoppelte, aber getrennte kennt der Data Layer nicht.
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
     * Was [setup] zuletzt ergab, `null` vor der ersten Antwort: Jede Seite des Assistenten fragt neu, beginnt aber mit dem
     * bekannten Stand statt mit „keine Uhr“ (sonst spränge die Zahl der Schritte, Review D27).
     */
    @Volatile
    var lastSetup: WatchSetup? = null
        private set
}

enum class WatchSetup { NONE, APP_MISSING, APP_INSTALLED }
