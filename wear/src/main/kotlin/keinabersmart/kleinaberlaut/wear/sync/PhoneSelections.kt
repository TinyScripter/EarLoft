package keinabersmart.kleinaberlaut.wear.sync

import com.google.android.gms.wearable.MessageClient
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectRequest
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ändert von der Uhr aus, was das Handy für sie auswählt (Schalter „Auf der Uhr“, Wear-Konzept 17): Album, Playlist oder
 * Favoriten. Das Handy schreibt die Auswahl und veröffentlicht das Manifest neu; die Uhr übernimmt es wie jede Auswahl.
 */
@Singleton
class PhoneSelections @Inject constructor(
    private val messageClient: MessageClient,
    private val phoneConnection: PhoneConnection,
) {
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
}
