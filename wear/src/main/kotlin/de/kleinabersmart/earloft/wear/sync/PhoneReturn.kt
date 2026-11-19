package de.kleinabersmart.earloft.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.NodeClient
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.wearsync.WearCodec
import de.kleinabersmart.earloft.core.wearsync.WearPaths
import de.kleinabersmart.earloft.core.wearsync.WearPhoneWantedDto
import de.kleinabersmart.earloft.core.wearsync.deleteOwn
import de.kleinabersmart.earloft.core.wearsync.putPayload
import de.kleinabersmart.earloft.wear.download.WatchState
import kotlinx.serialization.SerializationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Notices when the phone is back after the download job has been waiting for the phone after [WatchState.PHONE_ATTEMPTS] runs
 * (Review V1). Capabilities do not arrive everywhere (Wear concept 4); a dedicated DataItem does, and the Data Layer delivers it
 * as soon as the devices are connected again: the watch writes [WearPaths.PHONE_WANTED], the phone answers with
 * [WearPaths.PHONE_BACK], the watch syncs ([PhoneLibrarySync] detects the phone and replans). The watch process is
 * only woken in this case, not on every connect.
 */
@Singleton
class PhoneReturn @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
    private val state: WatchState,
) {
    /** From now on the job waits for the phone. */
    suspend fun announce() {
        dataClient.putPayload(WearPaths.PHONE_WANTED, WearCodec.encode(WearPhoneWantedDto(since = System.currentTimeMillis())))
    }

    /** The phone is back or there is no more waiting; a DataItem not yet delivered would otherwise arrive later. */
    suspend fun withdraw() {
        dataClient.deleteOwn(nodeClient, WearPaths.PHONE_WANTED)
    }

    /** The phone's answer to [WearPaths.PHONE_WANTED]. */
    fun onPhoneBack(payload: ByteArray) {
        val wanted = try {
            WearCodec.decode<WearPhoneWantedDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "Phone response not readable", e)
            return
        }
        when (phoneBackAction(state.waitingForPhone, wanted.since, System.currentTimeMillis())) {
            PhoneBackAction.IGNORE -> Log.d(TAG, "Phone checked in, but the watch is not waiting (any more)")
            PhoneBackAction.NEVER_AWAY ->
                // The DataItem arrived right away: the phone was reachable, the transfer failed for another reason. New
                // runs would fail as well; it stays with the charger or the next sync.
                Log.i(TAG, "Phone answered immediately, so it was reachable; no new run")
            PhoneBackAction.RESYNC -> {
                Log.i(TAG, "Phone is back, syncing")
                PhoneSyncWorker.enqueue(context)
            }
        }
    }

    private companion object {
        const val TAG = "PhoneReturn"
    }
}

internal enum class PhoneBackAction { IGNORE, NEVER_AWAY, RESYNC }

/**
 * What the phone's answer triggers. If it came within [PHONE_AWAY_MIN_MS] after the waiting began, the phone was never gone
 * (otherwise five new runs that fail again would start every few minutes).
 */
internal fun phoneBackAction(waiting: Boolean, since: Long, now: Long): PhoneBackAction = when {
    !waiting -> PhoneBackAction.IGNORE
    now - since in 0 until PHONE_AWAY_MIN_MS -> PhoneBackAction.NEVER_AWAY
    else -> PhoneBackAction.RESYNC
}

/**
 * A reachable phone gets an urgent DataItem within seconds (1–2 s on the emulator). Before the waiting there was already a
 * run in which the phone did not answer for 30 s; a longer limit would only miss a briefly absent phone.
 */
internal const val PHONE_AWAY_MIN_MS = 10_000L
