package keinabersmart.kleinaberlaut.wear

import android.util.Log
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.wear.PhoneFileLocator
import keinabersmart.kleinaberlaut.core.data.wear.WearServerMirror
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsDto
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsRequest
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.sendFile
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
    @Inject lateinit var channelClient: ChannelClient
    @Inject lateinit var fileLocator: PhoneFileLocator
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CREDENTIALS_REQUEST -> {
                val request = decodeOrNull<WearCredentialsRequest>(event) ?: return
                applicationScope.launch { sendPassword(event.sourceNodeId, request.sourceId) }
            }
        }
    }

    /**
     * Die Uhr holt eine Datei (`/kal/file/<sourceUid>`, Wear-Konzept 8.4): Das Handy schickt die lokale Datei oder
     * den fertigen Download im Original, ohne Datei nur die Kopfzeile „keine Datei“.
     */
    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val path = channel.path
        if (!path.startsWith(WearPaths.FILE_CHANNEL_PREFIX)) return
        val sourceUid = path.removePrefix(WearPaths.FILE_CHANNEL_PREFIX)
        applicationScope.launch {
            val file = fileLocator.open(sourceUid)
            Log.d(TAG, "Datei $sourceUid für die Uhr: ${file?.length?.let { "$it Bytes" } ?: "keine Datei"}")
            channelClient.sendFile(channel, file?.length ?: 0, file?.input)
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
