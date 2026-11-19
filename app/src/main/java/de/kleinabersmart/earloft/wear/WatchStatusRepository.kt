package de.kleinabersmart.earloft.wear

import android.util.Log
import com.google.android.gms.wearable.DataClient
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearStatusDto
import de.kleinabersmart.earloft.core.wearsync.changesAt
import de.kleinabersmart.earloft.core.wearsync.payloadsAt
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/** The watch's last status report: storage and progress per selection ([WearPaths.WATCH_STATUS]). */
@Singleton
class WatchStatusRepository @Inject constructor(private val dataClient: DataClient) {

    /** `null` as long as the watch hasn't reported anything yet. */
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
