package keinabersmart.kleinaberlaut.core.wearsync

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Kodierung der Nachrichten als JSON. Unbekannte Felder werden ignoriert, damit Handy und Uhr mit
 * unterschiedlichen App-Versionen zusammenarbeiten, solange [WearProtocol.VERSION] gleich ist.
 */
object WearCodec {
    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()

    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.decodeToString())

    /** Das Manifest kann mehrere MB groß werden; als gzip-Asset statt im DataItem (100-KB-Grenze). */
    fun encodeManifest(manifest: WearManifest): ByteArray = gzip(encode(manifest))

    fun decodeManifest(bytes: ByteArray): WearManifest = decode(gunzip(bytes))

    /** SHA-256 (hex) – stabil für gleiche Inhalte, damit das Handy unveränderte Manifeste nicht neu sendet. */
    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** [hash] des unkomprimierten Manifests (gzip enthält keine Zeitstempel, aber so bleibt es unabhängig davon). */
    fun manifestHash(manifest: WearManifest): String = hash(encode(manifest))

    /** Wie [encode], aber als gzip – für Nachrichten, die sonst über die Größe einer Message hinauswachsen (Fernsteuerung). */
    inline fun <reified T> encodeCompressed(value: T): ByteArray = gzip(encode(value))

    inline fun <reified T> decodeCompressed(bytes: ByteArray): T = decode(gunzip(bytes))

    /** Cover für die Fernsteuerung: die Album-ID (8 Byte) vor den JPEG-Bytes; ohne JPEG-Bytes gibt es kein Cover. */
    fun encodeCover(albumId: Long, jpeg: ByteArray): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES + jpeg.size).putLong(albumId).put(jpeg).array()

    /** @return Album-ID und JPEG-Bytes; `null`, wenn [bytes] zu kurz sind. */
    fun decodeCover(bytes: ByteArray): Pair<Long, ByteArray>? {
        if (bytes.size < Long.SIZE_BYTES) return null
        return ByteBuffer.wrap(bytes).getLong() to bytes.copyOfRange(Long.SIZE_BYTES, bytes.size)
    }

    @PublishedApi
    internal fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @PublishedApi
    internal fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
}
