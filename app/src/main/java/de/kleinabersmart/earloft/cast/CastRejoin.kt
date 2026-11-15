package de.kleinabersmart.earloft.cast

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hängt die App nach einem Neustart wieder an eine Cast-Sitzung, die beim Ende des Prozesses noch lief. Die Cast-Bibliothek
 * kann das nicht: Mit der Ausgabeauswahl des Systems beendet Play-Dienste die Sitzung beim Tod des Prozesses, der Empfänger
 * spielt aber weiter (am Gerät gesehen, 2026-11-13).
 *
 * Dazu merkt sich [remember] Gerät und Sitzung, solange gecastet wird, und [keepUntil], bis wann der Empfänger mit seiner
 * Warteschlange voraussichtlich noch spielt; ein reguläres Ende ([forget]) löscht beides. Beim Start des Players sucht
 * [select] das Gerät (höchstens [DISCOVERY_MS]), aber nur bis zu diesem Zeitpunkt, und wählt es nur aus, wenn dort noch die
 * gemerkte Sitzung läuft ([CastReceiverStatus]): Das Auswählen startet unsere App auf dem Empfänger und verdrängt dort eine
 * andere, die inzwischen läuft; beim Lösen danach endete auch der Empfänger. [CastSessionPlayerFactory] prüft nach dem
 * Verbinden trotzdem mit [isOurs], ob es die gemerkte Sitzung ist, und löst sich sonst wieder (eine selbst gestartete wird
 * dabei beendet).
 */
@Singleton
internal class CastRejoin @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var attempted = false
    private var writtenUntil = 0L

    /** Die beim letzten Ende des Prozesses offene Sitzung, solange [select] sie sucht oder die App sich gerade anhängt. */
    var pending: Saved? = null
        private set

    fun remember(session: CastSession, sessionId: String) {
        val deviceId = session.castDevice?.deviceId ?: return
        writtenUntil = System.currentTimeMillis() + IDLE_GRACE_MS
        prefs.edit { putString(KEY_DEVICE, deviceId).putString(KEY_SESSION, sessionId).putLong(KEY_UNTIL, writtenUntil) }
    }

    /**
     * Main-Thread. Der Empfänger spielt voraussichtlich bis [playingUntilMs] (Wanduhr; `null`: pausiert oder ohne
     * Warteschlange). Danach lohnt das Anhängen noch [IDLE_GRACE_MS], dann beendet er sich von selbst.
     */
    fun keepUntil(playingUntilMs: Long?) {
        if (!prefs.contains(KEY_SESSION)) return
        val until = (playingUntilMs ?: System.currentTimeMillis()) + IDLE_GRACE_MS
        // Höchstens etwa einmal je Minute schreiben; der Wert verschiebt sich beim Abspielen kaum.
        if (kotlin.math.abs(until - writtenUntil) < WRITE_STEP_MS) return
        writtenUntil = until
        prefs.edit { putLong(KEY_UNTIL, until) }
    }

    fun forget() = prefs.edit { clear() }

    /** Main-Thread. Ob [sessionId] die gemerkte Sitzung ist; beendet den Versuch. */
    fun isOurs(sessionId: String): Boolean {
        val saved = pending ?: return false
        pending = null
        return saved.sessionId == sessionId
    }

    /**
     * Main-Thread. Ob die gerade startende [session] das Anhängen ist (zwischen [select] und dem Ergebnis). Wählt jemand
     * inzwischen selbst ein anderes Gerät, ist der Versuch vorbei.
     */
    fun isRejoining(session: CastSession): Boolean {
        val saved = pending ?: return false
        if (session.castDevice?.deviceId == saved.deviceId) return true
        pending = null
        return false
    }

    /** Main-Thread. Einmal je Prozess: sucht das gemerkte Gerät und wählt es aus, wenn dort noch die gemerkte Sitzung läuft. */
    suspend fun select() {
        if (attempted) return
        attempted = true
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return
        val sessionId = prefs.getString(KEY_SESSION, null) ?: return
        if (System.currentTimeMillis() > prefs.getLong(KEY_UNTIL, 0)) {
            Log.i(TAG, "Letzte Sitzung ist vermutlich vorbei, hänge mich nicht wieder an")
            forget()
            return
        }
        val castContext = awaitCastContext() ?: return
        val selector = castContext.mergedSelector ?: return
        val router = MediaRouter.getInstance(context)
        // Läuft schon eine Sitzung oder hat jemand selbst ein Gerät gewählt, bleibt es dabei.
        fun busy() = castContext.sessionManager.currentCastSession != null || router.selectedRoute.matchesSelector(selector)
        fun isSaved(route: MediaRouter.RouteInfo) = CastDevice.getFromBundle(route.extras)?.deviceId == deviceId &&
            route.isEnabled && route.matchesSelector(selector)
        if (busy()) return
        val route = router.routes.firstOrNull(::isSaved) ?: run {
            val found = CompletableDeferred<MediaRouter.RouteInfo>()
            val callback = object : MediaRouter.Callback() {
                override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = check(route)
                override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = check(route)
                fun check(route: MediaRouter.RouteInfo) {
                    if (isSaved(route)) found.complete(route)
                }
            }
            router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
            try {
                withTimeoutOrNull(DISCOVERY_MS) { found.await() }
            } finally {
                router.removeCallback(callback)
            }
        }
        forget()
        if (route == null) {
            if (!busy()) Log.i(TAG, "Gerät der letzten Sitzung nicht gefunden")
            return
        }
        val running = CastDevice.getFromBundle(route.extras)?.let { CastReceiverStatus.sessionIds(it) }
        if (running == null || sessionId !in running) {
            Log.i(TAG, "Auf ${route.name} läuft die letzte Sitzung nicht mehr (${running ?: "Status unbekannt"}), hänge mich nicht an")
            return
        }
        if (busy()) return
        Log.i(TAG, "Hänge mich wieder an ${route.name}")
        val selected = Saved(deviceId, sessionId)
        pending = selected
        router.selectRoute(route)
        // Kommt auf das Auswählen keine Sitzung zustande (Route abgelehnt, ohne Rückmeldung), endet der Versuch hier;
        // sonst hielte er jede spätere, selbst gewählte Sitzung für das Anhängen.
        delay(JOIN_MS)
        if (pending === selected) pending = null
    }

    /** Main-Thread. Der Versuch ist gescheitert (Verbinden misslungen) oder die Sitzung ist zu Ende. */
    fun failed() {
        pending = null
    }

    private suspend fun awaitCastContext(): CastContext? {
        repeat(CONTEXT_TRIES) {
            CastContext.getSharedInstance()?.let { return it }
            delay(CONTEXT_POLL_MS)
        }
        return null
    }

    data class Saved(val deviceId: String, val sessionId: String)

    private companion object {
        const val PREFS = "cast_rejoin"
        const val KEY_DEVICE = "device"
        const val KEY_SESSION = "session"
        const val KEY_UNTIL = "until"
        const val DISCOVERY_MS = 15_000L
        const val JOIN_MS = 30_000L

        /** Etwa so lange bleibt ein Empfänger ohne Wiedergabe in der App, bevor er sich beendet. */
        const val IDLE_GRACE_MS = 10 * 60_000L
        const val WRITE_STEP_MS = 60_000L
        const val CONTEXT_TRIES = 20
        const val CONTEXT_POLL_MS = 250L
        const val TAG = "CastRejoin"
    }
}
