package keinabersmart.kleinaberlaut.wear.sync

import com.google.android.gms.wearable.MessageClient
import keinabersmart.kleinaberlaut.core.model.WearSelectionKind
import keinabersmart.kleinaberlaut.core.wearsync.WearClearRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearClearScope
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearSelectSpokenRequest
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ändert von der Uhr aus, was das Handy für sie auswählt (Schalter „Auf der Uhr“, Wear-Konzept 17): Album, Playlist,
 * Favoriten, Folge oder Buch. Das Handy schreibt die Auswahl und veröffentlicht das Manifest neu; die Uhr übernimmt es wie
 * jede Auswahl.
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

    /** Wählt Folge bzw. Buch [bookId] (ID am Handy) für die Uhr bzw. ab. @return `false`, wenn das Handy nicht erreichbar ist. */
    suspend fun setSpokenSelected(bookId: Long, selected: Boolean): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.SELECT_SPOKEN, WearCodec.encode(WearSelectSpokenRequest(bookId, selected)))
    }

    /**
     * Wählt Folge bzw. Buch über Server bzw. Abo und Kennung ab (Speicher-Ansicht, die ID am Handy kennt die Uhr nicht).
     * @return `false`, wenn das Handy nicht erreichbar ist.
     */
    suspend fun deselectSpoken(serverId: String, remoteId: String): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        val request = WearSelectSpokenRequest(selected = false, serverId = serverId, remoteId = remoteId)
        return messageClient.sendPayload(nodeId, WearPaths.SELECT_SPOKEN, WearCodec.encode(request))
    }

    /**
     * Entfernt am Handy alle Auswahlen bzw. die einer Art ([scope]); die Uhr räumt beim nächsten Manifest auf.
     * @return `false` ohne erreichbares Handy.
     */
    suspend fun clearSelections(scope: WearClearScope = WearClearScope.ALL): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.CLEAR_SELECTIONS, WearCodec.encode(WearClearRequest(scope)))
    }
}
