package de.kleinabersmart.earloft.wear.download

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** A requested Wi-Fi network; [close] releases the request again. */
class WifiLease internal constructor(
    val network: Network,
    private val close: () -> Unit,
) : AutoCloseable {
    override fun close() = close.invoke()
}

/**
 * Explicitly requests Wi-Fi (Wear concept 8.2). The Bluetooth connection to the phone also counts as a network
 * for WorkManager, but should not be used for large files. If the watch's Wi-Fi is turned off, the
 * system does **not** turn it on in response to this request (Pixel Watch 3, 2026-10-30): after [DEFAULT_TIMEOUT_MS] `null` is returned,
 * and the watch shows "Waiting for Wi-Fi" (`WatchState.waitingForWifi`) until the user turns it on themselves.
 */
@Singleton
class WifiNetwork @Inject constructor(@ApplicationContext private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** @return `null` if no Wi-Fi became available within [timeoutMs]. */
    suspend fun acquire(timeoutMs: Int = DEFAULT_TIMEOUT_MS): WifiLease? {
        debugNetwork()?.let { return it }
        return requestWifi(timeoutMs)
    }

    /**
     * Development only: the Wear emulator gets no Wi-Fi. In a debuggable build the watch then takes the
     * active network if the switch [DEBUG_ANY_NETWORK] is set in `watch_state`
     * (`adb shell run-as de.kleinabersmart.earloft`, edit preferences). Release builds ignore it.
     */
    private fun debugNetwork(): WifiLease? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val enabled = context.getSharedPreferences("watch_state", Context.MODE_PRIVATE).getBoolean(DEBUG_ANY_NETWORK, false)
        if (!debuggable || !enabled) return null
        Log.w(TAG, "Debug: using the active network instead of Wi-Fi")
        return connectivity.activeNetwork?.let { WifiLease(it) {} }
    }

    private suspend fun requestWifi(timeoutMs: Int): WifiLease? = suspendCancellableCoroutine { continuation ->
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "Wi-Fi available: $network")
                // If the caller is cancelled between this resume and its continuation, the lease never reaches it: release it
                // here, otherwise the request would stay until the process ends (review G28).
                val lease = WifiLease(network) { release(this) }
                if (continuation.isActive) continuation.resume(lease) { _, unused, _ -> unused.close() }
            }

            override fun onUnavailable() {
                Log.i(TAG, "No Wi-Fi within $timeoutMs ms")
                release(this)
                if (continuation.isActive) continuation.resume(null)
            }
        }
        continuation.invokeOnCancellation { release(callback) }
        try {
            connectivity.requestNetwork(request, callback, timeoutMs)
        } catch (e: SecurityException) {
            Log.w(TAG, "Requesting Wi-Fi not allowed", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun release(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Already released (timeout or called twice).
        }
    }

    private companion object {
        const val TAG = "WifiNetwork"
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val DEBUG_ANY_NETWORK = "debug_any_network"
    }
}
