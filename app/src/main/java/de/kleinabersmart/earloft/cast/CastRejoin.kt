package de.kleinabersmart.earloft.cast

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hängt die App nach einem Neustart wieder an eine Cast-Sitzung, die beim Ende des Prozesses noch lief. Die Cast-Bibliothek
 * kann das nicht: Mit der Ausgabeauswahl des Systems beendet Play-Dienste die Sitzung beim Tod des Prozesses, der Empfänger
 * spielt aber weiter (am Gerät gesehen, 2026-11-13).
 *
 * Dazu merkt sich [remember] Gerät und Sitzung, solange gecastet wird; ein reguläres Ende ([forget]) löscht sie. Beim Start
 * des Players wählt [select] das Gerät wieder aus, sobald es im Netz auftaucht (höchstens [DISCOVERY_MS]). Das Auswählen hängt
 * sich an die laufende App des Empfängers oder startet sie neu; [CastSessionPlayerFactory] prüft danach mit [isOurs], ob es
 * noch die gemerkte Sitzung ist, und löst sich sonst wieder (eine selbst gestartete wird dabei beendet).
 */
@Singleton
internal class CastRejoin @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var attempted = false

    /** Die beim letzten Ende des Prozesses offene Sitzung, solange [select] sie sucht oder die App sich gerade anhängt. */
    var pending: Saved? = null
        private set

    fun remember(session: CastSession, sessionId: String) {
        val deviceId = session.castDevice?.deviceId ?: return
        prefs.edit { putString(KEY_DEVICE, deviceId).putString(KEY_SESSION, sessionId) }
    }

    fun forget() = prefs.edit { clear() }

    /** Main-Thread. Ob [sessionId] die gemerkte Sitzung ist; beendet den Versuch. */
    fun isOurs(sessionId: String): Boolean {
        val saved = pending ?: return false
        pending = null
        return saved.sessionId == sessionId
    }

    /** Main-Thread. Ob die App sich gerade wieder anhängt (zwischen [select] und dem Ergebnis). */
    val isRejoining: Boolean get() = pending != null

    /** Main-Thread. Einmal je Prozess: sucht das gemerkte Gerät und wählt es aus. */
    suspend fun select() {
        if (attempted) return
        attempted = true
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return
        val sessionId = prefs.getString(KEY_SESSION, null) ?: return
        val castContext = awaitCastContext() ?: return
        if (castContext.sessionManager.currentCastSession != null) return
        val selector = castContext.mergedSelector ?: return
        val router = MediaRouter.getInstance(context)
        var selected = false
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = check(router, route)
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = check(router, route)

            fun check(router: MediaRouter, route: MediaRouter.RouteInfo) {
                if (selected) return
                if (CastDevice.getFromBundle(route.extras)?.deviceId != deviceId || !route.matchesSelector(selector)) return
                Log.i(TAG, "Hänge mich wieder an ${route.name}")
                router.removeCallback(this)
                forget()
                selected = true
                pending = Saved(deviceId, sessionId)
                router.selectRoute(route)
            }
        }
        router.routes.firstOrNull { CastDevice.getFromBundle(it.extras)?.deviceId == deviceId }?.let { callback.check(router, it) }
        if (selected) return
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        delay(DISCOVERY_MS)
        router.removeCallback(callback)
        if (!selected) {
            Log.i(TAG, "Gerät der letzten Sitzung nicht gefunden")
            forget()
        }
    }

    /** Main-Thread. Der Versuch ist gescheitert (Verbinden misslungen). */
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
        const val DISCOVERY_MS = 15_000L
        const val CONTEXT_TRIES = 20
        const val CONTEXT_POLL_MS = 250L
        const val TAG = "CastRejoin"
    }
}
