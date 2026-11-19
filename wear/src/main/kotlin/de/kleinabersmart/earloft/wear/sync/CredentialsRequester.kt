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
 * Asks the phone for a source's password, token or API key (Wear concept 6): when the watch lacks them, when they
 * changed on the phone and when the server rejected them. The answer arrives as a message at the [WatchListenerService] and
 * replaces the stored ones. If the phone is unreachable, the next occasion asks again.
 */
class CredentialsRequester @Inject constructor(
    @ApplicationContext context: Context,
    private val dataClient: DataClient,
    private val messageClient: MessageClient,
) {
    /** When missing credentials were last requested, per source. */
    private val asked = context.getSharedPreferences("credentials_requests", Context.MODE_PRIVATE)

    /** [phoneNodeId]: the phone the servers come from; if not given, the one that wrote the server list. */
    suspend fun request(sourceIds: Collection<String>, phoneNodeId: String? = null) {
        if (sourceIds.isEmpty()) return
        val nodeId = phoneNodeId ?: dataClient.payloadsAt(WearPaths.SERVERS).keys.firstOrNull() ?: return
        val now = System.currentTimeMillis()
        asked.edit().apply { sourceIds.forEach { putLong(it, now) } }.apply()
        sourceIds.forEach { sourceId ->
            Log.d(TAG, "Requesting credentials for $sourceId")
            messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS_REQUEST, WearCodec.encode(WearCredentialsRequest(sourceId)))
        }
    }

    /**
     * Like [request], but at most every [MISSING_RETRY_MS] per source: if the phone itself has no credentials (not yet
     * entered after a restore), it does not answer, and otherwise every start of the watch would wake the phone.
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
