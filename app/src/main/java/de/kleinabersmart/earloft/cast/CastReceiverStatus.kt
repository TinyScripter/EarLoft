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
 * Fragt einen Cast-Empfänger direkt, welche Sitzungen bei ihm laufen – nur lesend, über das Cast-Protokoll (TLS auf dem Port
 * des Geräts, Nachrichten als Protobuf `CastMessage` mit vier Byte Länge davor). Die Cast-Bibliothek kann das nur, indem sie
 * eine Sitzung startet, und das startet unsere App auf dem Empfänger. Den Statustext der Route (Zeile unter dem Namen)
 * aktualisieren die Play-Dienste nicht verlässlich: Am Emulator stand dort noch „Casting: <alter Titel>“, während längst eine
 * andere App lief (2026-11-15).
 *
 * Der Empfänger weist sich mit einem selbst signierten Zertifikat aus, dessen CN seine Geräte-ID ist (mit Bindestrichen, am
 * Gerät gesehen); nur das wird angenommen ([DeviceCertificate]).
 */
internal object CastReceiverStatus {

    /** IO. Die Kennungen der Sitzungen, die bei [device] laufen; `null`, wenn er nicht antwortet. */
    suspend fun sessionIds(device: CastDevice): Set<String>? = withContext(Dispatchers.IO) {
        try {
            query(device)
        } catch (e: IOException) {
            Log.w(TAG, "Status von ${device.friendlyName} nicht lesbar", e)
            null
        } catch (e: GeneralSecurityException) {
            Log.w(TAG, "Status von ${device.friendlyName} nicht lesbar", e)
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
            // Dazwischen können andere Nachrichten kommen (etwa PING).
            repeat(MAX_MESSAGES) {
                val length = input.readInt()
                if (length !in 0..MAX_MESSAGE_BYTES) throw IOException("Nachricht mit $length Byte")
                val message = decodeCastMessage(ByteArray(length).also(input::readFully))
                if (message.namespace != NAMESPACE_RECEIVER) return@repeat
                receiverSessionIds(message.payload)?.let { ids ->
                    output.writeMessage(NAMESPACE_CONNECTION, """{"type":"CLOSE"}""")
                    output.flush()
                    return ids
                }
            }
            throw IOException("Kein Status erhalten")
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
 * Nimmt nur das Zertifikat des Empfängers [deviceId] an: selbst signiert, gültig, CN gleich der Geräte-ID (ohne Bindestriche
 * verglichen, so meldet sie [CastDevice.getDeviceId]).
 */
private class DeviceCertificate(private val deviceId: String) : X509TrustManager {

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val certificate = chain?.singleOrNull() ?: throw CertificateException("Erwartet genau ein Zertifikat")
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        val commonName = certificate.subjectX500Principal.name.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=") }
            ?.removePrefix("CN=")
        if (commonName?.replace("-", "")?.equals(deviceId, ignoreCase = true) != true) {
            throw CertificateException("Zertifikat für $commonName, erwartet $deviceId")
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
        throw CertificateException("Nur für Verbindungen zum Empfänger")

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

internal class CastMessage(val namespace: String?, val payload: String?)

/** `CastMessage` mit Protokoll CASTV2_1_0, von `sender-0` an `receiver-0`, Text-Nutzlast. */
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

/** Liest Namensraum (Feld 4) und Text-Nutzlast (Feld 6) einer `CastMessage`; alles andere wird übersprungen. */
internal fun decodeCastMessage(bytes: ByteArray): CastMessage {
    var position = 0
    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= bytes.size || shift > 63) throw IOException("Ungültige Nachricht")
            val byte = bytes[position++].toInt()
            result = result or ((byte and 0x7F).toLong() shl shift)
            if (byte and 0x80 == 0) return result
            shift += 7
        }
    }
    fun skip(count: Long) {
        if (count < 0 || position + count > bytes.size) throw IOException("Ungültige Nachricht")
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
            else -> throw IOException("Ungültige Nachricht")
        }
    }
    return CastMessage(namespace, payload)
}

/** Aus einer Nachricht `RECEIVER_STATUS` die Kennungen der laufenden Sitzungen; `null` bei einer anderen Nachricht. */
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
