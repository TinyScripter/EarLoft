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
 * Chromecast Etappe 2 (Konzept 7.5): Songs, Bücher und Folgen, die nur auf dem Handy liegen (oder deren Server der Empfänger
 * nicht erreicht), lädt der Empfänger per HTTP vom Handy.
 *
 * - Der Server ([HttpFileServer]) startet erst, wenn in einer Sitzung eine Adresse gebraucht wird, und endet mit der Sitzung
 *   ([stop]). Jede Sitzung bekommt ein neues Geheimnis im Pfad ([newSession]); abrufbar ist nur, was in dieser Sitzung
 *   veröffentlicht wurde ([fileUrl], Cover über Album bzw. Buch). Die Adressen sind deshalb wie Zugangsdaten zu behandeln.
 * - Adresse ist die IPv4-Adresse des Handys im WLAN (bzw. Ethernet); ohne sie gibt es keine Adressen, und die Titel fallen
 *   beim Casten weg wie bisher. Im Debug-Build ersetzt `phone_address` (Host:Port) in den SharedPreferences `cast_debug` die
 *   angekündigte Adresse: Im Emulator erreicht der Empfänger das Handy nur über eine Weiterleitung auf dem Rechner.
 * - Solange der Server läuft und abgerufen wird, halten ein Partial-Wake-Lock und ein WLAN-Lock Prozessor und WLAN wach
 *   (wie ExoPlayer beim Streamen); sie laufen [LOCK_TIMEOUT_MS] nach dem letzten Abruf von selbst ab.
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

    @Suppress("DEPRECATION") // WIFI_MODE_FULL_HIGH_PERF: wie ExoPlayers WifiLockManager.
    private val wifiLock by lazy {
        context.applicationContext.getSystemService(WifiManager::class.java)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "EarLoft:cast-server")
            .apply { setReferenceCounted(false) }
    }

    /** Zu Beginn jeder Cast-Sitzung: neues Geheimnis; nichts von vorher bleibt abrufbar. */
    fun newSession() {
        stop()
        token = newToken()
        files.clear()
        keys.clear()
        covers.clear()
    }

    /** Am Ende der Sitzung. */
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

    /** `http://host:port/<Geheimnis>`; startet den Server bei Bedarf. */
    private fun baseUrl(): String? {
        val override = debugAddress()
        val host = override?.substringBeforeLast(':') ?: lanAddress() ?: return null
        val port = try {
            server.start(PREFERRED_PORT)
        } catch (e: IOException) {
            Log.w(TAG, "Server startet nicht", e)
            return null
        }
        keepAwake()
        val advertisedPort = override?.substringAfterLast(':')?.toIntOrNull() ?: port
        return "http://$host:$advertisedPort/$token"
    }

    /** Die IPv4-Adresse im WLAN bzw. Ethernet; über Mobilfunk erreicht der Empfänger das Handy nicht. */
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

    /** Auf einem Thread des Servers. */
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
                val descriptor = context.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Nicht lesbar")
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

    /** Verlängert die Locks bei jedem Abruf; ohne Abruf laufen sie ab. */
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
