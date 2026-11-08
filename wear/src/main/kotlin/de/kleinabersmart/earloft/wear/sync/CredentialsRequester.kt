package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.MessageClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearCredentialsRequest
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import de.kleinabersmart.earloft.core.wearsync.sendPayload
import javax.inject.Inject

/**
 * Fragt beim Handy nach Passwort, Token bzw. API-Schlüssel einer Quelle (Wear-Konzept 6): wenn sie der Uhr fehlen, wenn sie sich
 * am Handy geändert haben und wenn der Server sie abgelehnt hat. Die Antwort kommt als Message an den [WatchListenerService] und
 * ersetzt die gespeicherten. Ist das Handy nicht erreichbar, fragt der nächste Anlass erneut.
 */
class CredentialsRequester @Inject constructor(
    @ApplicationContext context: Context,
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
) {
    /** Wann zuletzt nach fehlenden Zugangsdaten je Quelle gefragt wurde. */
    private val asked = context.getSharedPreferences("credentials_requests", Context.MODE_PRIVATE)

    /** [phoneNodeId]: das Handy, von dem die Server stammen; ohne Angabe das, das die Serverliste geschrieben hat. */
    suspend fun request(sourceIds: Collection<String>, phoneNodeId: String? = null) {
        if (sourceIds.isEmpty()) return
        val nodeId = phoneNodeId ?: dataClient.payloadsAt(WearPaths.SERVERS).keys.firstOrNull() ?: return
        val now = System.currentTimeMillis()
        asked.edit().apply { sourceIds.forEach { putLong(it, now) } }.apply()
        sourceIds.forEach { sourceId ->
            Log.d(TAG, "Zugangsdaten für $sourceId anfordern")
            messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS_REQUEST, WearCodec.encode(WearCredentialsRequest(sourceId)))
        }
    }

    /**
     * Wie [request], aber je Quelle höchstens alle [MISSING_RETRY_MS]: Hat das Handy selbst keine Zugangsdaten (nach einer
     * Wiederherstellung noch nicht eingegeben), antwortet es nicht, und sonst weckte jeder Start der Uhr das Handy.
     */
    suspend fun requestMissing(sourceIds: Collection<String>, phoneNodeId: String? = null) {
        val now = System.currentTimeMillis()
        request(sourceIds.filter { now - asked.getLong(it, 0) !in 0 until MISSING_RETRY_MS }, phoneNodeId)
    }

    companion object {
        const val MISSING_RETRY_MS = 30 * 60 * 1000L
        private const val TAG = "CredentialsRequester"
    }
}
