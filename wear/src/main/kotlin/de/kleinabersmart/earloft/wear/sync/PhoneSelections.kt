package de.kleinabersmart.earloft.wear.sync

import com.google.android.gms.wearable.MessageClient
import de.kleinabersmart.earloft.core.model.WearSelectionKind
import de.kleinabersmart.earloft.core.wearsync.WearClearRequest
import de.kleinabersmart.earloft.core.wearsync.WearClearScope
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearSelectRequest
import de.kleinabersmart.earloft.core.wearsync.WearSelectSpokenRequest
import de.kleinabersmart.earloft.core.wearsync.sendPayload
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Changes from the watch what the phone selects for it ("On the watch" switch, Wear concept 17): album, playlist,
 * favorites, episode or book. The phone writes the selection and republishes the manifest; the watch applies it like
 * any selection.
 */
@Singleton
class PhoneSelections @Inject constructor(
    private val messageClient: MessageClient,
    private val phoneConnection: PhoneConnection,
) {
    /** @return `false` if the phone is currently unreachable; the selection then does not change. */
    suspend fun setSelected(kind: WearSelectionKind, refId: Long?, selected: Boolean): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.SELECT, WearCodec.encode(WearSelectRequest(kind, refId, selected)))
    }

    /** Selects or deselects episode or book [bookId] (ID on the phone) for the watch. @return `false` if the phone is unreachable. */
    suspend fun setSpokenSelected(bookId: Long, selected: Boolean): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.SELECT_SPOKEN, WearCodec.encode(WearSelectSpokenRequest(bookId, selected)))
    }

    /**
     * Deselects an episode or book via server or subscription and identifier (storage view; the watch does not know the ID on the phone).
     * @return `false` if the phone is unreachable.
     */
    suspend fun deselectSpoken(serverId: String, remoteId: String): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        val request = WearSelectSpokenRequest(selected = false, serverId = serverId, remoteId = remoteId)
        return messageClient.sendPayload(nodeId, WearPaths.SELECT_SPOKEN, WearCodec.encode(request))
    }

    /**
     * Removes all selections or those of one kind ([scope]) on the phone; the watch cleans up with the next manifest.
     * @return `false` without a reachable phone.
     */
    suspend fun clearSelections(scope: WearClearScope = WearClearScope.ALL): Boolean {
        val nodeId = phoneConnection.connectedPhoneNodeId() ?: return false
        return messageClient.sendPayload(nodeId, WearPaths.CLEAR_SELECTIONS, WearCodec.encode(WearClearRequest(scope)))
    }
}
