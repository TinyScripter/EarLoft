package de.kleinabersmart.earloft.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.RouteInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import android.util.LruCache
import androidx.core.graphics.scale
import dagger.hilt.android.qualifiers.ApplicationContext
import de.kleinabersmart.earloft.core.data.repository.ArtworkRepository
import de.kleinabersmart.earloft.core.data.repository.PhoneCastServer
import de.kleinabersmart.earloft.core.data.spoken.BookCovers
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chromecast stage 2 (concept 7.5): songs, books and episodes that exist only on the phone (or whose server the receiver
 * can't reach) are loaded by the receiver from the phone via HTTP.
 *
 * - The server ([HttpFileServer]) only starts once an address is needed in a session, and stops with the session
 *   ([stop]); outside a session there are no addresses (a late resolve after the end doesn't start it again).
 *   Each session gets a new secret in the path ([newSession]); only what was published in this session
 *   can be fetched ([fileUrl], the covers of published albums and books). The addresses must therefore be treated like credentials.
 * - The address is the phone's IPv4 address on Wi-Fi (or Ethernet), preferably the one in the receiver's subnet; VPN networks
 *   don't count, their tunnel address is out of the receiver's reach ([CastLanAddress], review J12). Without it there are no
 *   addresses, and the titles are dropped when casting as before. In the debug build, `phone_address` (host:port) in the SharedPreferences `cast_debug` replaces
 *   the announced address: in the emulator the receiver can only reach the phone through port forwarding on the host.
 * - While the server is running and being accessed, a partial wake lock and a Wi-Fi lock keep CPU and Wi-Fi awake
 *   (like ExoPlayer when streaming); they expire by themselves [LOCK_TIMEOUT_MS] after the last delivery
 *   ([HttpFileServer.onActivity], also renewed during a long transfer).
 */
@Singleton
internal class PhoneMediaServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artwork: ArtworkRepository,
    private val bookCovers: BookCovers,
) : PhoneCastServer {

    private class PublishedFile(val uri: Uri, val mimeType: String)

    private val server = HttpFileServer(::resolve).apply { onActivity = ::keepAwake }
    private val random = SecureRandom()

    @Volatile
    private var token = newToken()

    /** Between [newSession] and [stop]. */
    @Volatile
    private var active = false
    private val files = ConcurrentHashMap<String, PublishedFile>()
    private val keys = ConcurrentHashMap<String, String>()
    private val nextKey = AtomicInteger()

    /** `album:<id>` / `book:<id>` whose cover address was handed out in this session. */
    private val publishedCovers = ConcurrentHashMap.newKeySet<String>()
    private val covers = object : LruCache<String, ByteArray>(COVER_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** The LAN address with the time it was determined; [baseUrl] is called per title (on the main thread for books). */
    @Volatile
    private var host: Pair<String, Long>? = null

    /** The receiver's address in this session, to pick the phone's address in the same subnet. */
    @Volatile
    private var receiver: InetAddress? = null

    private val wakeLock by lazy {
        context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EarLoft:cast-server")
            .apply { setReferenceCounted(false) }
    }

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: like ExoPlayer's WifiLockManager.
    private val wifiLock by lazy {
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EarLoft:cast-server")
            .apply { setReferenceCounted(false) }
    }

    /** At the start of every cast session: new secret; nothing from before remains accessible. */
    fun newSession(receiver: InetAddress?) {
        stop()
        this.receiver = receiver
        token = newToken()
        files.clear()
        keys.clear()
        publishedCovers.clear()
        covers.evictAll()
        active = true
    }

    /** Resuming a session: within the process everything stays; after a process restart it starts like a new one. */
    fun resumeSession(receiver: InetAddress?) {
        if (!active) {
            newSession(receiver)
        } else if (receiver != null && receiver != this.receiver) {
            this.receiver = receiver
            host = null
        }
    }

    /** At the end of the session. */
    fun stop() {
        synchronized(lockGuard) { active = false }
        host = null
        server.stop()
        releaseLocks()
    }

    /** [keepAwake] (server threads) and [stop] check [active] and take the locks only together (review G40). */
    private val lockGuard = Any()

    override fun fileUrl(uri: Uri, mimeType: String): String? {
        val base = baseUrl() ?: return null
        val key = keys.computeIfAbsent("$mimeType $uri") {
            nextKey.incrementAndGet().toString().also { files[it] = PublishedFile(uri, mimeType) }
        }
        return "$base/f/$key"
    }

    override fun albumCoverUrl(albumId: Long): String? =
        baseUrl()?.let { publishedCovers += "$COVER_ALBUM:$albumId"; "$it/c/$COVER_ALBUM/$albumId" }

    override fun bookCoverUrl(bookId: Long): String? =
        baseUrl()?.let { publishedCovers += "$COVER_BOOK:$bookId"; "$it/c/$COVER_BOOK/$bookId" }

    /** `http://host:port/<secret>`; starts the server if needed. `null` outside a session. */
    private fun baseUrl(): String? {
        if (!active) return null
        val override = debugAddress()
        val host = override?.substringBeforeLast(':') ?: currentLanAddress() ?: return null
        val port = try {
            server.start(PREFERRED_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Server does not start", e)
            return null
        }
        // The session may have ended meanwhile (resolving runs off the main thread).
        if (!active) {
            server.stop()
            return null
        }
        val advertisedPort = override?.substringAfterLast(':')?.toIntOrNull() ?: port
        return "http://$host:$advertisedPort/$token"
    }

    /** [lanAddress], remembered for [HOST_CACHE_MS] (a network change in between is rare; the next title then has the new one). */
    private fun currentLanAddress(): String? {
        val now = System.currentTimeMillis()
        host?.takeIf { now - it.second < HOST_CACHE_MS }?.let { return it.first }
        return lanAddress()?.also { host = it to now }
    }

    /**
     * The IPv4 address on Wi-Fi or Ethernet, chosen by [CastLanAddress]. All networks, not just the default one: with a VPN the
     * default network is the tunnel, and the Wi-Fi underneath is only one of the others.
     */
    private fun lanAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val default = connectivity.activeNetwork
        @Suppress("DEPRECATION") // A one-off snapshot; a NetworkCallback would have to be kept registered for the session.
        val networks = connectivity.allNetworks
        val candidates = networks.flatMap { network ->
            val capabilities = connectivity.getNetworkCapabilities(network) ?: return@flatMap emptyList()
            val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            val vpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                (link.address as? Inet4Address)?.let { LocalAddress(it, link.prefixLength, local, vpn, network == default) }
            }
        }
        return CastLanAddress.pick(candidates, receiver)?.hostAddress.also {
            Log.i(TAG, "Announcing $it to receiver $receiver (candidates: ${candidates.joinToString { c -> "${c.address.hostAddress}/${c.prefixLength} local=${c.local} vpn=${c.vpn}" }})")
        }
    }

    /**
     * Whether the app's traffic goes through a VPN that also takes the receiver's address into its tunnel. The receiver then
     * can't load anything from the phone, whichever address is announced; the user can only change it in the VPN app (exclude
     * EarLoft or local networks). `false` without a session, receiver or VPN.
     */
    fun receiverBehindVpn(): Boolean {
        val receiver = receiver ?: return false
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return false
        val routes = connectivity.getLinkProperties(network)?.routes.orEmpty().mapNotNull { route ->
            val destination = route.destination.address
            // Excluded routes (`VpnService.Builder.excludeRoute`) only exist from API 33 on, like the route type.
            val captures = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || route.type != RouteInfo.RTN_THROW
            VpnRoute(destination, route.destination.prefixLength, captures)
        }
        return CastLanAddress.vpnCapturesReceiver(routes, receiver).also {
            Log.i(TAG, "Receiver $receiver ${if (it) "is" else "is not"} routed through the VPN")
        }
    }

    private fun debugAddress(): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getString(DEBUG_ADDRESS, null)?.takeIf { ':' in it }
    }

    /** On a server thread. */
    private fun resolve(path: String): HttpFileServer.Resource? {
        if (!active) return null
        val parts = path.trim('/').split('/')
        if (parts.size < 3 || parts[0] != token) return null
        return when (parts[1]) {
            "f" -> files[parts[2]]?.let(::openFile)
            "c" -> if (parts.size == 4) cover(parts[2], parts[3].toLongOrNull() ?: return null) else null
            else -> null
        }
    }

    private fun openFile(file: PublishedFile): HttpFileServer.Resource? {
        val uri = file.uri
        if (uri.scheme == "file") {
            val local = uri.path?.let(::File)?.takeIf { it.isFile } ?: return null
            return object : HttpFileServer.Resource {
                override val mimeType = file.mimeType
                override val length = local.length()
                override fun open(offset: Long): InputStream = FileInputStream(local).apply { channel.position(offset) }
            }
        }
        val length = context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize }?.takeIf { it >= 0 } ?: return null
        return object : HttpFileServer.Resource {
            override val mimeType = file.mimeType
            override val length = length
            override fun open(offset: Long): InputStream {
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Not readable")
                return ParcelFileDescriptor.AutoCloseInputStream(descriptor).apply { channel.position(offset) }
            }
        }
    }

    private fun cover(kind: String, id: Long): HttpFileServer.Resource? {
        val key = "$kind:$id"
        // Only covers of this session's titles, not the whole library by counting up the ids.
        if (key !in publishedCovers) return null
        val bytes = covers[key] ?: run {
            val bitmap = runBlocking {
                when (kind) {
                    COVER_ALBUM -> artwork.albumCover(id, COVER_SIZE_PX)
                    COVER_BOOK -> bookCovers.cover(id)
                    else -> null
                }
            } ?: return null
            jpeg(bitmap).also { covers.put(key, it) }
        }
        return HttpFileServer.BytesResource("image/jpeg", bytes)
    }

    private fun jpeg(bitmap: Bitmap): ByteArray {
        val scaled = if (maxOf(bitmap.width, bitmap.height) > COVER_SIZE_PX) {
            val factor = COVER_SIZE_PX.toFloat() / maxOf(bitmap.width, bitmap.height)
            bitmap.scale((bitmap.width * factor).toInt(), (bitmap.height * factor).toInt())
        } else {
            bitmap
        }
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Extends the locks on every request; without requests they expire. */
    @SuppressLint("WakelockTimeout")
    private fun keepAwake() {
        synchronized(lockGuard) {
            // Without the guard a request finishing right after [stop] could take both locks again for up to 10 min.
            if (!active) return
            wakeLock.acquire(LOCK_TIMEOUT_MS)
            if (!wifiLock.isHeld) wifiLock.acquire()
            lockReleaser.removeCallbacksAndMessages(null)
            lockReleaser.postDelayed(::releaseLocks, LOCK_TIMEOUT_MS)
        }
    }

    private val lockReleaser by lazy { Handler(Looper.getMainLooper()) }

    private fun releaseLocks() {
        synchronized(lockGuard) {
            lockReleaser.removeCallbacksAndMessages(null)
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    private fun newToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PhoneMediaServer"
        const val PREFERRED_PORT = 47321
        const val COVER_SIZE_PX = 512
        const val LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        const val HOST_CACHE_MS = 5_000L
        const val COVER_CACHE_BYTES = 8 * 1024 * 1024
        const val COVER_ALBUM = "album"
        const val COVER_BOOK = "book"
        const val DEBUG_PREFS = "cast_debug"
        const val DEBUG_ADDRESS = "phone_address"
    }
}
