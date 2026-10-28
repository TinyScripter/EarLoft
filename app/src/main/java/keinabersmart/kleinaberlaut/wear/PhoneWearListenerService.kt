package keinabersmart.kleinaberlaut.wear

import android.util.Log
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.wear.WearServerMirror
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.sendPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject

/**
 * Nachrichten der Uhr ans Handy (Wear-Konzept 4). Die Play-Dienste starten den Dienst bei Bedarf, auch
 * wenn die App nicht läuft. Zugestellt wird nur zwischen Apps mit gleicher applicationId und Signatur.
 */
@AndroidEntryPoint
class PhoneWearListenerService : WearableListenerService() {

    @Inject lateinit var serverMirror: WearServerMirror
    @Inject lateinit var messageClient: MessageClient
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CREDENTIALS_REQUEST -> {
                val request = decodeOrNull<WearCredentialsRequest>(event) ?: return
                applicationScope.launch { sendPassword(event.sourceNodeId, request.sourceId) }
            }
        }
    }

    /** Das Passwort geht nur als Message an genau die anfragende Uhr, nie als DataItem (Wear-Konzept 4). */
    private suspend fun sendPassword(nodeId: String, sourceId: String) {
        val password = serverMirror.password(sourceId)
        if (password == null) {
            Log.w(TAG, "Kein Passwort für $sourceId")
            return
        }
        messageClient.sendPayload(nodeId, WearPaths.CREDENTIALS, WearCodec.encode(WearCredentialsDto(sourceId, password)))
    }

    private inline fun <reified T> decodeOrNull(event: MessageEvent): T? = try {
        WearCodec.decode<T>(event.data)
    } catch (e: SerializationException) {
        Log.w(TAG, "${event.path} nicht lesbar", e)
        null
    } catch (e: IllegalArgumentException) {
        Log.w(TAG, "${event.path} nicht lesbar", e)
        null
    }

    private companion object {
        const val TAG = "PhoneWearListener"
    }
}
