package keinabersmart.kleinaberlaut.wear.download

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

/** Ein angefordertes WLAN; [close] gibt die Anforderung wieder frei. */
class WifiLease internal constructor(
    val network: Network,
    private val close: () -> Unit,
) : AutoCloseable {
    override fun close() = close.invoke()
}

/**
 * Fordert ausdrücklich WLAN an (Wear-Konzept 8.2). Die Bluetooth-Verbindung zum Handy gilt für WorkManager
 * ebenfalls als Netz, soll aber für große Dateien nicht genutzt werden; das System schaltet WLAN dafür bei Bedarf ein.
 */
@Singleton
class WifiNetwork @Inject constructor(@ApplicationContext private val context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    /** @return `null`, wenn innerhalb von [timeoutMs] kein WLAN verfügbar wurde. */
    suspend fun acquire(timeoutMs: Int = DEFAULT_TIMEOUT_MS): WifiLease? {
        debugNetwork()?.let { return it }
        return requestWifi(timeoutMs)
    }

    /**
     * Nur für Entwicklung: Der Wear-Emulator bekommt kein WLAN. In einem debuggable Build nimmt die Uhr dann das
     * aktive Netz, wenn in `watch_state` der Schalter [DEBUG_ANY_NETWORK] gesetzt ist
     * (`adb shell run-as keinabersmart.kleinaberlaut`, Preferences bearbeiten). Release-Builds ignorieren ihn.
     */
    private fun debugNetwork(): WifiLease? {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        val enabled = context.getSharedPreferences("watch_state", Context.MODE_PRIVATE).getBoolean(DEBUG_ANY_NETWORK, false)
        if (!debuggable || !enabled) return null
        Log.w(TAG, "Debug: nehme das aktive Netz statt WLAN")
        return connectivity.activeNetwork?.let { WifiLease(it) {} }
    }

    private suspend fun requestWifi(timeoutMs: Int): WifiLease? = suspendCancellableCoroutine { continuation ->
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "WLAN verfügbar: $network")
                if (continuation.isActive) continuation.resume(WifiLease(network) { release(this) })
            }

            override fun onUnavailable() {
                Log.i(TAG, "Kein WLAN innerhalb von $timeoutMs ms")
                release(this)
                if (continuation.isActive) continuation.resume(null)
            }
        }
        continuation.invokeOnCancellation { release(callback) }
        try {
            connectivity.requestNetwork(request, callback, timeoutMs)
        } catch (e: SecurityException) {
            Log.w(TAG, "WLAN anfordern nicht erlaubt", e)
            if (continuation.isActive) continuation.resume(null)
        }
    }

    private fun release(callback: ConnectivityManager.NetworkCallback) {
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // Schon freigegeben (Zeitüberschreitung oder doppelt aufgerufen).
        }
    }

    private companion object {
        const val TAG = "WifiNetwork"
        const val DEFAULT_TIMEOUT_MS = 30_000
        const val DEBUG_ANY_NETWORK = "debug_any_network"
    }
}
