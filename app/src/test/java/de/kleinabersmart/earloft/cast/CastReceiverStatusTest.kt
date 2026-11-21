package de.kleinabersmart.earloft.cast

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class CastReceiverStatusTest {

    @Test
    fun `a message survives encoding and decoding`() {
        val message = decodeCastMessage(encodeCastMessage("urn:x-cast:com.google.cast.receiver", """{"type":"GET_STATUS","requestId":1}"""))

        assertEquals("urn:x-cast:com.google.cast.receiver", message.namespace)
        assertEquals("""{"type":"GET_STATUS","requestId":1}""", message.payload)
    }

    @Test
    fun `long payloads use multi-byte lengths`() {
        val payload = "ä".repeat(300)

        assertEquals(payload, decodeCastMessage(encodeCastMessage("ns", payload)).payload)
    }

    @Test
    fun `the running sessions come from the receiver status`() {
        // Shortened, as reported by the device ("Badezimmer Mini", another app in the Default Media Receiver).
        val status = """{"requestId":1,"status":{"applications":[{"appId":"CC1AD845","displayName":"Default Media Receiver",
            |"sessionId":"a1baa014-22be-4da3-9ff1-069ed9e225eb","statusText":"Streamen: Anderer Titel",
            |"transportId":"a1baa014-22be-4da3-9ff1-069ed9e225eb"}],"volume":{"level":0.0}},"type":"RECEIVER_STATUS"}""".trimMargin()

        assertEquals(setOf("a1baa014-22be-4da3-9ff1-069ed9e225eb"), receiverSessionIds(status))
        assertEquals(emptySet<String>(), receiverSessionIds("""{"type":"RECEIVER_STATUS","status":{"volume":{"level":0.1}}}"""))
    }

    @Test
    fun `other messages are not a status`() {
        assertNull(receiverSessionIds("""{"type":"PING"}"""))
        assertNull(receiverSessionIds("not json"))
        assertNull(receiverSessionIds(null))
    }

    @Test(timeout = 2_000)
    fun `a length that overflows the position is rejected instead of looping`() {
        // Field 1, length-delimited, length 2^63 - 10: position (10) + length overflows to a negative number.
        val bytes = mutableListOf<Byte>(0x0A).apply {
            var rest = Long.MAX_VALUE - 9
            while (rest and 0x7FL.inv() != 0L) {
                add(((rest and 0x7F) or 0x80).toByte())
                rest = rest ushr 7
            }
            add(rest.toByte())
        }.toByteArray()

        assertThrows(IOException::class.java) { decodeCastMessage(bytes) }
    }
}
