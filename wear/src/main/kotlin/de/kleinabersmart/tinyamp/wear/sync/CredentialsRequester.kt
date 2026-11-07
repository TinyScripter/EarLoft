package de.kleinabersmart.tinyamp.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import de.kleinabersmart.tinyamp.core.wearsync.WearCodec
import de.kleinabersmart.tinyamp.core.wearsync.WearCredentialsRequest
import de.kleinabersmart.tinyamp.core.wearsync.WearPaths
import de.kleinabersmart.tinyamp.core.wearsync.payloadsAt
import de.kleinabersmart.tinyamp.core.wearsync.sendPayload
import javax.inject.Inject

/**
 * Fragt beim Handy nach Passwort, Token bzw. API-Schlüssel einer Quelle (Wear-Konzept 6): wenn sie der Uhr fehlen und
 * wenn der Server sie abgelehnt hat (neues Passwort, neu angemeldet). Die Antwort kommt als Message an den
 * [WatchListenerService] und ersetzt die gespeicherten. Ist das Handy nicht erreichbar, fragt der nächste Anlass erneut.
 */
class CredentialsRequester @Inject constructor(
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
) {
    /** [phoneNodeId]: das Handy, von dem die Server stammen; ohne Angabe das, das die Serverliste geschrieben hat. */
    suspend fun request(sourceIds: Collection<String>, phoneNodeId: String? = null) {
        if (sourceIds.isEmpty()) return
        val nodeId = phoneNodeId ?: dataClient.payloadsAt(WearPaths.SERVERS).keys.firstOrNull() ?: return
        sourceIds.forEach { sourceId ->
            Log.d(TAG, "Zugangsdaten für $sourceId anfordern")
            messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS_REQUEST, WearCodec.encode(WearCredentialsRequest(sourceId)))
        }
    }

    private companion object {
        const val TAG = "CredentialsRequester"
    }
}
