package keinabersmart.kleinaberlaut.wear

import android.util.Log
import com.google.android.gms.wearable.DataClient
import keinabersmart.kleinaberlaut.core.wearsync.WearCodec
import keinabersmart.kleinaberlaut.core.wearsync.WearPaths
import keinabersmart.kleinaberlaut.core.wearsync.WearStatusDto
import keinabersmart.kleinaberlaut.core.wearsync.changesAt
import keinabersmart.kleinaberlaut.core.wearsync.payloadsAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/** Der letzte Statusbericht der Uhr: Speicher und Fortschritt je Auswahl ([WearPaths.WATCH_STATUS]). */
@Singleton
class WatchStatusRepository @Inject constructor(private val dataClient: DataClient) {

    /** `null`, solange die Uhr noch nichts gemeldet hat. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val status: Flow<WearStatusDto?> = dataClient.changesAt(WearPaths.WATCH_STATUS)
        .mapLatest {
            val payload = dataClient.payloadsAt(WearPaths.WATCH_STATUS).values.firstOrNull() ?: return@mapLatest null
            try {
                WearCodec.decode<WearStatusDto>(payload)
            } catch (e: SerializationException) {
                Log.w(TAG, "Statusbericht der Uhr nicht lesbar", e)
                null
            }
        }
        .distinctUntilChanged()

    private companion object {
        const val TAG = "WatchStatusRepository"
    }
}
