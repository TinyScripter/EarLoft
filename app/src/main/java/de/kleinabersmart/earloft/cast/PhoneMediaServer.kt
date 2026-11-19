package de.kleinabersmart.earloft.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
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
 *   ([stop]). Each session gets a new secret in the path ([newSession]); only what was published in this session
 *   can be fetched ([fileUrl], covers via album or book). The addresses must therefore be treated like credentials.
 * - The address is the phone's IPv4 address on Wi-Fi (or Ethernet); without it there are no addresses, and the titles are
 *   dropped when casting as before. In the debug build, `phone_address` (host:port) in the SharedPreferences `cast_debug` replaces
 *   the announced address: in the emulator the receiver can only reach the phone through port forwarding on the host.
 * - While the server is running and being accessed, a partial wake lock and a Wi-Fi lock keep CPU and Wi-Fi awake
 *   (like ExoPlayer when streaming); they expire by themselves [LOCK_TIMEOUT_MS] after the last request.
 */
@Singleton
internal class PhoneMediaServer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val artwork: ArtworkRepository,
    private val bookCovers: BookCovers,
) : PhoneCastServer {

    private class PublishedFile(val uri: Uri, val mimeType: String)

    private val server = HttpFileServer(::resolve).apply { onRequest = ::keepAwake }
    private val random = SecureRandom()

    @Volatile
    private var token = newToken()
    private val files = ConcurrentHashMap<String, PublishedFile>()
    private val keys = ConcurrentHashMap<String, String>()
    private val nextKey = AtomicInteger()
    private val covers = ConcurrentHashMap<String, ByteArray>()

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
    fun newSession() {
        stop()
        token = newToken()
        files.clear()
        keys.clear()
        covers.clear()
    }

    /** At the end of the session. */
    fun stop() {
        server.stop()
        releaseLocks()
    }

    override fun fileUrl(uri: Uri, mimeType: String): String? {
        val base = baseUrl() ?: return null
        val key = keys.computeIfAbsent("$mimeType $uri") {
            nextKey.incrementAndGet().toString().also { files[it] = PublishedFile(uri, mimeType) }
        }
        return "$base/f/$key"
    }

    override fun albumCoverUrl(albumId: Long): String? = baseUrl()?.let { "$it/c/album/$albumId" }

    override fun bookCoverUrl(bookId: Long): String? = baseUrl()?.let { "$it/c/book/$bookId" }

    /** `http://host:port/<secret>`; starts the server if needed. */
    private fun baseUrl(): String? {
        val override = debugAddress()
        val host = override?.substringBeforeLast(':') ?: lanAddress() ?: return null
        val port = try {
            server.start(PREFERRED_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Server does not start", e)
            return null
        }
        keepAwake()
        val advertisedPort = override?.substringAfterLast(':')?.toIntOrNull() ?: port
        return "http://$host:$advertisedPort/$token"
    }

    /** The IPv4 address on Wi-Fi or Ethernet; the receiver can't reach the phone over mobile data. */
    private fun lanAddress(): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        val local = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!local) return null
        return connectivity.getLinkProperties(network)?.linkAddresses
            ?.map { it.address }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    private fun debugAddress(): String? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getString(DEBUG_ADDRESS, null)?.takeIf { ':' in it }
    }

    /** On a server thread. */
    private fun resolve(path: String): HttpFileServer.Resource? {
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
        val bytes = covers[key] ?: run {
            val bitmap = runBlocking {
                when (kind) {
                    "album" -> artwork.albumCover(id, COVER_SIZE_PX)
                    "book" -> bookCovers.cover(id)
                    else -> null
                }
            } ?: return null
            jpeg(bitmap).also { covers[key] = it }
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
        wakeLock.acquire(LOCK_TIMEOUT_MS)
        if (!wifiLock.isHeld) wifiLock.acquire()
        lockReleaser.removeCallbacksAndMessages(null)
        lockReleaser.postDelayed(::releaseLocks, LOCK_TIMEOUT_MS)
    }

    private val lockReleaser by lazy { Handler(Looper.getMainLooper()) }

    private fun releaseLocks() {
        lockReleaser.removeCallbacksAndMessages(null)
        if (wakeLock.isHeld) wakeLock.release()
        if (wifiLock.isHeld) wifiLock.release()
    }

    private fun newToken(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "PhoneMediaServer"
        const val PREFERRED_PORT = 47321
        const val COVER_SIZE_PX = 512
        const val LOCK_TIMEOUT_MS = 10 * 60 * 1000L
        const val DEBUG_PREFS = "cast_debug"
        const val DEBUG_ADDRESS = "phone_address"
    }
}
