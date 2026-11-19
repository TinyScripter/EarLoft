package de.kleinabersmart.earloft.cast

import android.util.Log
import com.google.android.gms.cast.CastDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.security.GeneralSecurityException
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Asks a cast receiver directly which sessions are running on it – read-only, via the Cast protocol (TLS on the
 * device's port, messages as protobuf `CastMessage` prefixed with a four-byte length). The Cast library can only do this by
 * starting a session, and that launches our app on the receiver. Play services don't reliably update the route's status
 * text (the line below the name): on the emulator it still said "Casting: <old title>" while another app had long
 * been running (2026-11-15).
 *
 * The receiver identifies itself with a self-signed certificate whose CN is its device ID (with hyphens, seen on the
 * device); only that is accepted ([DeviceCertificate]).
 */
internal object CastReceiverStatus {

    /** IO. The IDs of the sessions running on [device]; `null` if it doesn't answer. */
    suspend fun sessionIds(device: CastDevice): Set<String>? = withContext(Dispatchers.IO) {
        try {
            query(device)
        } catch (e: IOException) {
            Log.w(TAG, "Status of ${device.friendlyName} not readable", e)
            null
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Status of ${device.friendlyName} not readable", e)
            null
        }
    }

    private fun query(device: CastDevice): Set<String> {
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(DeviceCertificate(device.deviceId)), null) }
        (tls.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(device.inetAddress, device.servicePort), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            socket.startHandshake()
            val output = DataOutputStream(socket.outputStream.buffered())
            output.writeMessage(NAMESPACE_CONNECTION, """{"type":"CONNECT"}""")
            output.writeMessage(NAMESPACE_RECEIVER, """{"type":"GET_STATUS","requestId":1}""")
            output.flush()
            val input = DataInputStream(socket.inputStream.buffered())
            // Other messages (such as PING) can arrive in between.
            repeat(MAX_MESSAGES) {
                val length = input.readInt()
                if (length !in 0..MAX_MESSAGE_BYTES) throw IOException("Message with $length bytes")
                val message = decodeCastMessage(ByteArray(length).also(input::readFully))
                if (message.namespace != NAMESPACE_RECEIVER) return@repeat
                receiverSessionIds(message.payload)?.let { ids ->
                    output.writeMessage(NAMESPACE_CONNECTION, """{"type":"CLOSE"}""")
                    output.flush()
                    return ids
                }
            }
            throw IOException("No status received")
        }
    }

    private fun DataOutputStream.writeMessage(namespace: String, payload: String) {
        val bytes = encodeCastMessage(namespace, payload)
        writeInt(bytes.size)
        write(bytes)
    }

    private const val NAMESPACE_CONNECTION = "urn:x-cast:com.google.cast.tp.connection"
    private const val NAMESPACE_RECEIVER = "urn:x-cast:com.google.cast.receiver"
    private const val TIMEOUT_MS = 3_000
    private const val MAX_MESSAGES = 10
    private const val MAX_MESSAGE_BYTES = 64 * 1024
    private const val TAG = "CastReceiverStatus"
}

/**
 * Accepts only the certificate of the receiver [deviceId]: self-signed, valid, CN equal to the device ID (compared
 * without hyphens, which is how [CastDevice.getDeviceId] reports it).
 */
private class DeviceCertificate(private val deviceId: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.singleOrNull() ?: throw CertificateException("Expected exactly one certificate")
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val commonName = certificate.subjectX500Principal.name.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.removePrefix("CN=")
        if (commonName?.replace("-", "")?.equals(deviceId, ignoreCase = true) != true) {
            throw CertificateException("Certificate for $commonName, expected $deviceId")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Only for connections to the receiver")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class CastMessage(val namespace: String?, val payload: String?)

/** `CastMessage` with protocol CASTV2_1_0, from `sender-0` to `receiver-0`, text payload. */
internal fun encodeCastMessage(namespace: String, payload: String): ByteArray {
    val out = ByteArrayOutputStream()
    fun varint(value: Int) {
        var rest = value
        while (rest and 0x7F.inv() != 0) {
            out.write((rest and 0x7F) or 0x80)
            rest = rest ushr 7
        }
        out.write(rest)
    }
    fun text(field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        varint(field shl 3 or 2)
        varint(bytes.size)
        out.write(bytes)
    }
    varint(1 shl 3)
    varint(0)
    text(2, "sender-0")
    text(3, "receiver-0")
    text(4, namespace)
    varint(5 shl 3)
    varint(0)
    text(6, payload)
    return out.toByteArray()
}

/** Reads the namespace (field 4) and text payload (field 6) of a `CastMessage`; everything else is skipped. */
internal fun decodeCastMessage(bytes: ByteArray): CastMessage {
    var position = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size || shift > 63) throw IOException("Invalid message")
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
    fun skip(count: Long) {
        if (count < 0 || position + count > bytes.size) throw IOException("Invalid message")
        position += count.toInt()
    }
    var namespace: String? = null
    var payload: String? = null
    while (position < bytes.size) {
        val tag = varint()
        when ((tag and 7).toInt()) {
            0 -> varint()
            1 -> skip(8)
            2 -> {
                val length = varint()
                val start = position
                skip(length)
                when ((tag ushr 3).toInt()) {
                    4 -> namespace = String(bytes, start, length.toInt(), Charsets.UTF_8)
                    6 -> payload = String(bytes, start, length.toInt(), Charsets.UTF_8)
                }
            }
            5 -> skip(4)
            else -> throw IOException("Invalid message")
        }
    }
    return CastMessage(namespace, payload)
}

/** The IDs of the running sessions from a `RECEIVER_STATUS` message; `null` for any other message. */
internal fun receiverSessionIds(payload: String?): Set<String>? {
    val root = try {
        Json.parseToJsonElement(payload ?: return null) as? JsonObject
    } catch (_: SerializationException) {
        null
    } ?: return null
    if ((root["type"] as? JsonPrimitive)?.contentOrNull != "RECEIVER_STATUS") return null
    val applications = ((root["status"] as? JsonObject)?.get("applications") as? JsonArray) ?: return emptySet()
    return applications.mapNotNullTo(HashSet()) { ((it as? JsonObject)?.get("sessionId") as? JsonPrimitive)?.contentOrNull }
}
