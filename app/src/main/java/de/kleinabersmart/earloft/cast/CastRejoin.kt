package de.kleinabersmart.earloft.cast

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.mediarouter.media.MediaRouteSelector
import androidx.mediarouter.media.MediaRouter
import com.google.android.gms.cast.CastDevice
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastSession
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a restart, rejoins the app to a cast session that was still running when the process ended. The Cast library
 * can't do that: with the system output switcher, Play services end the session when the process dies, but the receiver
 * keeps playing (seen on the device, 2026-11-13).
 *
 * For this, [remember] stores device and session while casting, and [keepUntil] until when the receiver will probably
 * still be playing its queue; a regular end ([forget]) clears both. When the player starts,
 * [select] looks for the device (at most [DISCOVERY_MS], see [findNowOrInForeground]), but only up to that point in time, and selects it only if the
 * remembered session is still running there ([CastReceiverStatus]): selecting launches our app on the receiver and displaces
 * another one that may be running there by now; detaching afterwards would end the receiver too. [CastSessionPlayerFactory] still
 * checks with [isOurs] after connecting whether it is the remembered session, and detaches otherwise (one it started itself
 * is ended in the process).
 */
@Singleton
internal class CastRejoin @Inject constructor(@param:ApplicationContext private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var attempted = false
    private var writtenUntil = 0L

    /** The session open when the process last ended, while [select] is looking for it or the app is rejoining. */
    var pending: Saved? = null
        private set

    fun remember(session: CastSession, sessionId: String) {
        val deviceId = session.castDevice?.deviceId ?: return
        writtenUntil = System.currentTimeMillis() + IDLE_GRACE_MS
        prefs.edit { putString(KEY_DEVICE, deviceId).putString(KEY_SESSION, sessionId).putLong(KEY_UNTIL, writtenUntil) }
    }

    /**
     * Main thread. The receiver will probably play until [playingUntilMs] (wall clock; `null`: paused or without
     * a queue). After that, rejoining is still worthwhile for [IDLE_GRACE_MS], then it shuts down by itself.
     */
    fun keepUntil(playingUntilMs: Long?) {
        if (!prefs.contains(KEY_SESSION)) return
        val until = (playingUntilMs ?: System.currentTimeMillis()) + IDLE_GRACE_MS
        // Write at most about once per minute; the value barely shifts during playback.
        if (kotlin.math.abs(until - writtenUntil) < WRITE_STEP_MS) return
        writtenUntil = until
        prefs.edit { putLong(KEY_UNTIL, until) }
    }

    fun forget() = prefs.edit { clear() }

    /** Main thread. Whether [sessionId] is the remembered session; ends the attempt. */
    fun isOurs(sessionId: String): Boolean {
        val saved = pending ?: return false
        pending = null
        return saved.sessionId == sessionId
    }

    /**
     * Main thread. Whether the [session] currently starting is the rejoin (between [select] and the result). If someone
     * picks a different device themselves in the meantime, the attempt is over.
     */
    fun isRejoining(session: CastSession): Boolean {
        val saved = pending ?: return false
        if (session.castDevice?.deviceId == saved.deviceId) return true
        pending = null
        return false
    }

    /** Main thread. Once per process: looks for the remembered device and selects it if the remembered session is still running there. */
    suspend fun select() {
        if (attempted) return
        attempted = true
        val deviceId = prefs.getString(KEY_DEVICE, null) ?: return
        val sessionId = prefs.getString(KEY_SESSION, null) ?: return
        if (System.currentTimeMillis() > prefs.getLong(KEY_UNTIL, 0)) {
            Log.i(TAG, "Last session is probably over, not rejoining")
            forget()
            return
        }
        val castContext = awaitCastContext() ?: return
        val selector = castContext.mergedSelector ?: return
        val router = MediaRouter.getInstance(context)
        // If a session is already running or someone has picked a device themselves, leave it at that.
        fun busy() = castContext.sessionManager.currentCastSession != null || router.selectedRoute.matchesSelector(selector)
        fun isSaved(route: MediaRouter.RouteInfo) = CastDevice.getFromBundle(route.extras)?.deviceId == deviceId &&
            route.isEnabled && route.matchesSelector(selector)
        fun expired() = System.currentTimeMillis() > prefs.getLong(KEY_UNTIL, 0)
        if (busy()) return
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        val route = findNowOrInForeground(
            inForeground = { lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) },
            awaitForeground = {
                Log.i(TAG, "Device of the last session not found in the background, looking again once the app is opened")
                lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.STARTED) }
            },
        ) {
            if (busy() || expired()) null else router.routes.firstOrNull(::isSaved) ?: discover(router, selector, ::isSaved)
        }
        // A session started in the meantime remembers itself; clearing now would forget it.
        if (busy()) return
        val over = expired()
        forget()
        if (route == null) {
            Log.i(TAG, if (over) "Last session is probably over, not rejoining" else "Device of the last session not found")
            return
        }
        val running = CastDevice.getFromBundle(route.extras)?.let { CastReceiverStatus.sessionIds(it) }
        if (running == null || sessionId !in running) {
            Log.i(TAG, "The last session no longer runs on ${route.name} (${running ?: "status unknown"}), not rejoining")
            return
        }
        if (busy()) return
        Log.i(TAG, "Rejoining ${route.name}")
        val selected = Saved(deviceId, sessionId)
        pending = selected
        router.selectRoute(route)
        // If selecting doesn't result in a session (route rejected without feedback), the attempt ends here;
        // otherwise it would take any later, user-chosen session for the rejoin.
        delay(JOIN_MS)
        if (pending === selected) pending = null
    }

    private suspend fun discover(
        router: MediaRouter,
        selector: MediaRouteSelector,
        isSaved: (MediaRouter.RouteInfo) -> Boolean,
    ): MediaRouter.RouteInfo? {
        val found = CompletableDeferred<MediaRouter.RouteInfo>()
        val callback = object : MediaRouter.Callback() {
            override fun onRouteAdded(router: MediaRouter, route: MediaRouter.RouteInfo) = check(route)
            override fun onRouteChanged(router: MediaRouter, route: MediaRouter.RouteInfo) = check(route)
            fun check(route: MediaRouter.RouteInfo) {
                if (isSaved(route)) found.complete(route)
            }
        }
        router.addCallback(selector, callback, MediaRouter.CALLBACK_FLAG_REQUEST_DISCOVERY)
        return try {
            withTimeoutOrNull(DISCOVERY_MS) { found.await() }
        } finally {
            router.removeCallback(callback)
        }
    }

    /** Main thread. The attempt failed (connecting failed) or the session has ended. */
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

        /** Roughly how long a receiver stays in the app without playback before it shuts down. */
        const val IDLE_GRACE_MS = 10 * 60_000L
        const val WRITE_STEP_MS = 60_000L
        const val CONTEXT_TRIES = 20
        const val CONTEXT_POLL_MS = 250L
        const val TAG = "CastRejoin"
    }
}

/**
 * [find] once; if that finds nothing while the app is in the background, once more as soon as it is opened. Route discovery
 * found nothing within 15 s for an app restarted in the background (sticky playback service, no activity), but did within
 * 2 s in the foreground (seen on the device, 2026-11-28). Forgetting the session then would have lost the rejoin for good.
 */
internal suspend fun <T : Any> findNowOrInForeground(
    inForeground: () -> Boolean,
    awaitForeground: suspend () -> Unit,
    find: suspend () -> T?,
): T? {
    find()?.let { return it }
    if (inForeground()) return null
    awaitForeground()
    return find()
}
