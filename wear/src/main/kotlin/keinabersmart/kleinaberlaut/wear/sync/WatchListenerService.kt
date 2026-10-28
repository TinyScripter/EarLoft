package keinabersmart.kleinaberlaut.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import keinabersmart.kleinaberlaut.core.data.di.ApplicationScope
import keinabersmart.kleinaberlaut.core.data.wear.WearServerMirror
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearCredentialsDto
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject

/**
 * Nimmt Änderungen und Nachrichten vom Handy entgegen (Wear-Konzept 4). Die Play-Dienste starten den
 * Dienst bei Bedarf, auch wenn die App nicht läuft.
 */
@AndroidEntryPoint
class WatchListenerService : WearableListenerService() {

    @Inject lateinit var serverMirror: WearServerMirror
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onDataChanged(events: DataEventBuffer) {
        // Die Ereignisse nur hier lesen; der Puffer wird danach freigegeben.
        val paths = events.mapNotNull { it.dataItem.uri.path }
        if (paths.any { it == WearPaths.SERVERS || it == WearPaths.MANIFEST || it == WearPaths.SETTINGS }) PhoneSyncWorker.enqueue(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.CREDENTIALS) return
        val credentials = try {
            WearCodec.decode<WearCredentialsDto>(event.data)
        } catch (e: SerializationException) {
            Log.w(TAG, "Zugangsdaten nicht lesbar", e)
            return
        }
        applicationScope.launch {
            // Das Passwort liegt danach nur im Keystore der Uhr (KeystoreSecretStore).
            if (!serverMirror.storePassword(credentials.sourceId, credentials.password)) {
                Log.w(TAG, "Passwort für unbekannten Server ${credentials.sourceId} verworfen")
            }
        }
    }

    private companion object {
        const val TAG = "WatchListenerService"
    }
}
