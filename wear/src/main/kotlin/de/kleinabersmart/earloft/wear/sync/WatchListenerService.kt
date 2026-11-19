package de.kleinabersmart.earloft.wear.sync

import android.util.Log
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dagger.hilt.android.AndroidEntryPoint
import de.kleinabersmart.earloft.core.data.di.ApplicationScope
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.data.wear.WearServerMirror
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearCredentialsDto
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import javax.inject.Inject

/**
 * Receives changes and messages from the phone (Wear concept 4). Play services start the
 * service when needed, even if the app is not running.
 */
@AndroidEntryPoint
class WatchListenerService : WearableListenerService() {

    @Inject lateinit var serverMirror: WearServerMirror
    @Inject lateinit var audiobookshelf: AudiobookshelfServers
    @Inject lateinit var phoneReturn: PhoneReturn
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onDataChanged(events: DataEventBuffer) {
        // Read the events only here; the buffer is released afterwards.
        val paths = events.mapNotNull { it.dataItem.uri.path }
        if (paths.any { it == WearPaths.SERVERS || it == WearPaths.MANIFEST || it == WearPaths.SETTINGS || it == WearPaths.PODCASTS }) {
            PhoneSyncWorker.enqueue(this)
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            WearPaths.CREDENTIALS -> storeCredentials(event)
            WearPaths.PHONE_BACK -> phoneReturn.onPhoneBack(event.data)
        }
    }

    private fun storeCredentials(event: MessageEvent) {
        val credentials = try {
            WearCodec.decode<WearCredentialsDto>(event.data)
        } catch (e: SerializationException) {
            Log.w(TAG, "Zugangsdaten nicht lesbar", e)
            return
        }
        applicationScope.launch {
            // The password or API key (Audiobookshelf, 16.2) is then only stored in the watch's keystore (KeystoreSecretStore).
            if (!serverMirror.storePassword(credentials.sourceId, credentials.password, credentials.deviceId) &&
                !audiobookshelf.storeKey(credentials.sourceId, credentials.password)
            ) {
                Log.w(TAG, "Passwort für unbekannten Server ${credentials.sourceId} verworfen")
            }
        }
    }

    private companion object {
        const val TAG = "WatchListenerService"
    }
}
