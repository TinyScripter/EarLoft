package keinabersmart.kleinaberlaut.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearCatalogDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectRequest
import keinabersmart.kleinaberlaut.core.wearsync.changesAt
import keinabersmart.kleinaberlaut.core.wearsync.payloadsAt
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Der Katalog des Handys (Favoriten und Playlists, Wear-Konzept 5) und das Ändern der Auswahl von der Uhr aus.
 * Das Handy schreibt die Auswahl und veröffentlicht Katalog und Manifest neu; die Uhr wartet darauf.
 */
@Singleton
class PhoneCatalog @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
    private val phoneConnection: PhoneConnection,
) {
    /** Der zuletzt vom Handy geschriebene Katalog; `null`, solange das Handy noch keinen geschrieben hat. */
    val catalog: Flow<WearCatalogDto?> = dataClient.changesAt(WearPaths.CATALOG).map { read() }

    private suspend fun read(): WearCatalogDto? {
        val payload = dataClient.payloadsAt(WearPaths.CATALOG).values.firstOrNull() ?: return null
        return try {
            WearCodec.decode<WearCatalogDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "Katalog nicht lesbar", e)
            null
        }
    }

    /** @return `false`, wenn das Handy gerade nicht erreichbar ist; die Auswahl ändert sich dann nicht. */
    suspend fun setSelected(kind: WearSelectionKind, refId: Long?, selected: Boolean): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.SELECT, WearCodec.encode(WearSelectRequest(kind, refId, selected)))
    }

    /** Entfernt alle Auswahlen am Handy; die Uhr räumt beim nächsten Manifest auf. @return `false` ohne erreichbares Handy. */
    suspend fun clearSelections(): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.CLEAR_SELECTIONS, ByteArray(0))
    }

    private companion object {
        const val TAG = "PhoneCatalog"
    }
}
