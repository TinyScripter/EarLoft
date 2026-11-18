package de.kleinabersmart.earloft.cast

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

@RunWith(RobolectricTestRunner::class)
class HttpFileServerTest {

    private val bytes = ByteArray(1000) { it.toByte() }
    private val server = HttpFileServer { path ->
        if (path == "/secret/f/1") HttpFileServer.BytesResource("audio/mpeg", bytes) else null
    }
    private val port = server.start(0)

    @After
    fun tearDown() = server.stop()

    private fun get(path: String, range: String? = null, method: String = "GET"): HttpURLConnection =
        (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            range?.let { setRequestProperty("Range", it) }
        }

    @Test
    fun `the whole file with its type and length`() {
        val connection = get("/secret/f/1")

        assertEquals(200, connection.responseCode)
        assertEquals("audio/mpeg", connection.contentType)
        assertEquals("1000", connection.getHeaderField("Content-Length"))
        assertEquals("bytes", connection.getHeaderField("Accept-Ranges"))
        assertArrayEquals(bytes, connection.inputStream.readBytes())
    }

    @Test
    fun `a byte range for seeking`() {
        val connection = get("/secret/f/1", range = "bytes=100-199")

        assertEquals(206, connection.responseCode)
        assertEquals("bytes 100-199/1000", connection.getHeaderField("Content-Range"))
        assertArrayEquals(bytes.copyOfRange(100, 200), connection.inputStream.readBytes())
    }

    @Test
    fun `an open range runs to the end`() {
        val connection = get("/secret/f/1", range = "bytes=990-")

        assertEquals(206, connection.responseCode)
        assertArrayEquals(bytes.copyOfRange(990, 1000), connection.inputStream.readBytes())
    }

    @Test
    fun `a range behind the end is refused`() {
        assertEquals(416, get("/secret/f/1", range = "bytes=1000-").responseCode)
    }

    @Test
    fun `HEAD answers without a body`() {
        val connection = get("/secret/f/1", method = "HEAD")

        assertEquals(200, connection.responseCode)
        assertEquals("1000", connection.getHeaderField("Content-Length"))
    }

    @Test
    fun `unknown paths and a wrong secret are not found`() {
        assertEquals(404, get("/secret/f/2").responseCode)
        assertEquals(404, get("/other/f/1").responseCode)
    }

    @Test
    fun `other methods are not allowed`() {
        assertEquals(405, get("/secret/f/1", method = "PUT").responseCode)
    }

    @Test
    fun `several requests on one connection`() {
        Socket("127.0.0.1", port).use { socket ->
            val output = socket.getOutputStream()
            val input = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
            repeat(2) {
                output.write("GET /secret/f/1 HTTP/1.1\r\nHost: x\r\nRange: bytes=0-1\r\n\r\n".toByteArray())
                output.flush()
                assertEquals("HTTP/1.1 206 Partial Content", input.readLine())
                var header = input.readLine()
                while (header.isNotEmpty()) header = input.readLine()
                assertEquals(0, input.read())
                assertEquals(1, input.read())
            }
        }
    }

    @Test
    fun `parseByteRange follows the standard`() {
        assertEquals(ByteRange.Whole, parseByteRange(null, 1000))
        assertEquals(ByteRange.Partial(0, 999), parseByteRange("bytes=0-", 1000))
        assertEquals(ByteRange.Partial(500, 999), parseByteRange("bytes=500-5000", 1000))
        assertEquals(ByteRange.Partial(900, 999), parseByteRange("bytes=-100", 1000))
        assertEquals(ByteRange.Partial(0, 999), parseByteRange("bytes=-5000", 1000))
        assertEquals(ByteRange.Unsatisfiable, parseByteRange("bytes=1000-1001", 1000))
        assertEquals(ByteRange.Whole, parseByteRange("bytes=0-1,5-6", 1000))
        assertEquals(ByteRange.Whole, parseByteRange("items=0-1", 1000))
        assertEquals(ByteRange.Whole, parseByteRange("bytes=5-1", 1000))
    }

    @Test
    fun `after stop nothing is served`() {
        server.stop()
        assertNull(server.port)
    }
}
