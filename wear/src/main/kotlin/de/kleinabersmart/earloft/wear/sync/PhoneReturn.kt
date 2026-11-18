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
 * Merkt, wann das Handy zurück ist, nachdem der Download-Auftrag nach [WatchState.PHONE_ATTEMPTS] Läufen aufs Handy wartet
 * (Review V1). Capabilities kommen nicht überall an (Wear-Konzept 4); ein eigenes DataItem schon, und der Data Layer stellt es
 * zu, sobald die Geräte wieder verbunden sind: Die Uhr schreibt [WearPaths.PHONE_WANTED], das Handy antwortet mit
 * [WearPaths.PHONE_BACK], die Uhr gleicht ab ([PhoneLibrarySync] erkennt das Handy und plant neu). Der Prozess der Uhr wird
 * nur in diesem Fall geweckt, nicht bei jedem Verbinden.
 */
@Singleton
class PhoneReturn @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val dataClient: DataClient,
    private val nodeClient: NodeClient,
    private val state: WatchState,
) {
    /** Der Auftrag wartet ab jetzt aufs Handy. */
    suspend fun announce() {
        dataClient.putPayload(WearPaths.PHONE_WANTED, WearCodec.encode(WearPhoneWantedDto(since = System.currentTimeMillis())))
    }

    /** Das Handy ist zurück bzw. es wird nicht mehr gewartet; ein noch nicht zugestelltes DataItem käme sonst später an. */
    suspend fun withdraw() {
        dataClient.deleteOwn(nodeClient, WearPaths.PHONE_WANTED)
    }

    /** Antwort des Handys auf [WearPaths.PHONE_WANTED]. */
    fun onPhoneBack(payload: ByteArray) {
        val wanted = try {
            WearCodec.decode<WearPhoneWantedDto>(payload)
        } catch (e: SerializationException) {
            Log.w(TAG, "Antwort des Handys nicht lesbar", e)
            return
        }
        when (phoneBackAction(state.waitingForPhone, wanted.since, System.currentTimeMillis())) {
            PhoneBackAction.IGNORE -> Log.d(TAG, "Handy meldet sich, die Uhr wartet aber nicht (mehr)")
            PhoneBackAction.NEVER_AWAY ->
                // Das DataItem kam gleich an: Das Handy war erreichbar, die Übertragung scheiterte aus einem anderen Grund. Neue
                // Läufe scheiterten ebenso; es bleibt beim Ladegerät bzw. beim nächsten Abgleich.
                Log.i(TAG, "Handy antwortet sofort, war also erreichbar; kein neuer Lauf")
            PhoneBackAction.RESYNC -> {
                Log.i(TAG, "Handy ist zurück, gleiche ab")
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
 * Was die Antwort des Handys auslöst. Kam sie binnen [PHONE_AWAY_MIN_MS] nach dem Beginn des Wartens, war das Handy nie weg
 * (sonst begännen alle paar Minuten fünf neue Läufe, die wieder scheitern).
 */
internal fun phoneBackAction(waiting: Boolean, since: Long, now: Long): PhoneBackAction = when {
    !waiting -> PhoneBackAction.IGNORE
    now - since in 0 until PHONE_AWAY_MIN_MS -> PhoneBackAction.NEVER_AWAY
    else -> PhoneBackAction.RESYNC
}

/**
 * Ein erreichbares Handy bekommt ein dringendes DataItem binnen Sekunden (am Emulator 1–2 s). Vor dem Warten lag schon ein
 * Lauf, in dem das Handy 30 s lang nicht antwortete; eine längere Grenze verpasste nur ein kurz abwesendes Handy.
 */
internal const val PHONE_AWAY_MIN_MS = 10_000L
