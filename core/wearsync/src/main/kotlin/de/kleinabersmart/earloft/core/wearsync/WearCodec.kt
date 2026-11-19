package de.kleinabersmart.earloft.core.wearsync

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Encoding of the messages as JSON. Unknown fields are ignored so that phone and watch with
 * different app versions work together as long as [WearProtocol.VERSION] is the same.
 */
object WearCodec {
    @PublishedApi
    internal val json = Json {
        ignoreUnknownKeys = true
        // An enum value from a newer app version becomes the field's default instead of making everything unreadable.
        coerceInputValues = true
        encodeDefaults = true
        explicitNulls = false
    }

    inline fun <reified T> encode(value: T): ByteArray = json.encodeToString(value).encodeToByteArray()

    inline fun <reified T> decode(bytes: ByteArray): T = json.decodeFromString(bytes.decodeToString())

    /** The manifest can grow to several MB; sent as a gzip asset instead of in the DataItem (100 KB limit). */
    fun encodeManifest(manifest: WearManifest): ByteArray = gzip(encode(manifest))

    fun decodeManifest(bytes: ByteArray): WearManifest = decode(gunzip(bytes))

    /** SHA-256 (hex) – stable for identical content, so the phone doesn't resend unchanged manifests. */
    fun hash(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** [hash] of the uncompressed manifest (gzip contains no timestamps, but this way it stays independent of that). */
    fun manifestHash(manifest: WearManifest): String = hash(encode(manifest))

    /** Like [encode], but as gzip – for messages that would otherwise outgrow the size of a message (remote control). */
    inline fun <reified T> encodeCompressed(value: T): ByteArray = gzip(encode(value))

    inline fun <reified T> decodeCompressed(bytes: ByteArray): T = decode(gunzip(bytes))

    /**
     * Cover for the remote control: the key ([WearRemoteStateDto.coverKey], 8 bytes) before the JPEG bytes; without
     * JPEG bytes there is no cover.
     */
    fun encodeCover(coverKey: Long, jpeg: ByteArray): ByteArray =
        ByteBuffer.allocate(Long.SIZE_BYTES + jpeg.size).putLong(coverKey).put(jpeg).array()

    /** @return key and JPEG bytes; `null` if [bytes] is too short. */
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
