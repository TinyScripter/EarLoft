package de.kleinabersmart.earloft.cast

import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * A small HTTP/1.1 server for a cast receiver (Chromecast stage 2): only GET and HEAD, one byte range per request
 * (`Range: bytes=…`, for seeking), keep-alive. What lives under a path is decided by [resolve] (without query); everything else
 * is 404. One thread per connection, at most [MAX_CONNECTIONS].
 */
internal class HttpFileServer(private val resolve: (path: String) -> Resource?) {

    /** [length] bytes of type [mimeType]; [open] delivers them starting at [offset]. */
    interface Resource {
        val mimeType: String
        val length: Long
        fun open(offset: Long): InputStream
    }

    /** A byte array, e.g. a cover. */
    class BytesResource(override val mimeType: String, private val bytes: ByteArray) : Resource {
        override val length: Long get() = bytes.size.toLong()
        override fun open(offset: Long): InputStream = bytes.inputStream(offset.toInt(), bytes.size - offset.toInt())
    }

    private var socket: ServerSocket? = null
    private var executor: ThreadPoolExecutor? = null

    /** Called after every answered request (extends the wake lock). */
    var onRequest: () -> Unit = {}

    /** The port the server is listening on; `null` while it isn't running. */
    @get:Synchronized
    val port: Int?
        get() = socket?.takeUnless { it.isClosed }?.localPort

    /** Starts on [preferredPort] if it is free, otherwise on a free port. If already running, nothing changes. */
    @Synchronized
    fun start(preferredPort: Int): Int {
        port?.let { return it }
        val server = try {
            ServerSocket(preferredPort)
        } catch (e: IOException) {
            Log.i(TAG, "Port $preferredPort in use, taking a free one", e)
            ServerSocket(0)
        }
        val pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue()) { runnable ->
            Thread(runnable, "cast-http").apply { isDaemon = true }
        }
        socket = server
        executor = pool
        Thread({ acceptLoop(server, pool) }, "cast-http-accept").apply { isDaemon = true }.start()
        return server.localPort
    }

    @Synchronized
    fun stop() {
        runCatching { socket?.close() }
        executor?.shutdownNow()
        socket = null
        executor = null
    }

    private fun acceptLoop(server: ServerSocket, pool: ThreadPoolExecutor) {
        while (!server.isClosed) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (!server.isClosed) Log.w(TAG, "accept", e)
                return
            }
            try {
                pool.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = IDLE_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream(), BUFFER_SIZE)
            while (true) {
                val request = readRequest(input) ?: break
                val keepAlive = respond(request, output)
                output.flush()
                onRequest()
                if (!keepAlive) break
            }
        } catch (e: SocketException) {
            // The receiver closed the connection (seeking, next title).
        } catch (e: IOException) {
            Log.d(TAG, "Connection ended: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    /** @return whether the connection stays open. */
    private fun respond(request: Request, output: OutputStream): Boolean {
        val keepAlive = request.keepAlive
        if (request.method == "OPTIONS") {
            writeHead(output, "204 No Content", keepAlive, listOf("Access-Control-Allow-Headers" to "Range", "Content-Length" to "0"))
            return keepAlive
        }
        if (request.method != "GET" && request.method != "HEAD") {
            writeHead(output, "405 Method Not Allowed", false, listOf("Allow" to "GET, HEAD, OPTIONS", "Content-Length" to "0"))
            return false
        }
        val resource = try {
            resolve(request.path)
        } catch (e: Exception) {
            Log.w(TAG, "Not readable", e)
            null
        }
        if (resource == null) {
            writeHead(output, "404 Not Found", keepAlive, listOf("Content-Length" to "0"))
            return keepAlive
        }
        val length = resource.length
        val common = listOf("Content-Type" to resource.mimeType, "Accept-Ranges" to "bytes", "Cache-Control" to "no-store")
        val (status, start, count, extra) = when (val range = parseByteRange(request.headers["range"], length)) {
            ByteRange.Whole -> Head("200 OK", 0, length, emptyList())
            is ByteRange.Partial -> Head(
                "206 Partial Content", range.start, range.endInclusive - range.start + 1,
                listOf("Content-Range" to "bytes ${range.start}-${range.endInclusive}/$length"),
            )
            ByteRange.Unsatisfiable -> {
                writeHead(output, "416 Range Not Satisfiable", keepAlive, listOf("Content-Range" to "bytes */$length", "Content-Length" to "0"))
                return keepAlive
            }
        }
        writeHead(output, status, keepAlive, common + extra + ("Content-Length" to count.toString()))
        if (request.method == "GET" && count > 0) resource.open(start).use { copy(it, output, count) }
        return keepAlive
    }

    private data class Head(val status: String, val start: Long, val count: Long, val headers: List<Pair<String, String>>)

    private fun writeHead(output: OutputStream, status: String, keepAlive: Boolean, headers: List<Pair<String, String>>) {
        val text = buildString {
            append("HTTP/1.1 ").append(status).append("\r\n")
            headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("Access-Control-Allow-Origin: *\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
    }

    private fun copy(input: InputStream, output: OutputStream, count: Long) {
        val buffer = ByteArray(BUFFER_SIZE)
        var left = count
        while (left > 0) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (read < 0) throw IOException("File shorter than stated")
            output.write(buffer, 0, read)
            left -= read
        }
    }

    private class Request(val method: String, val path: String, val headers: Map<String, String>, val keepAlive: Boolean)

    /** Reads a request up to the blank line; `null` at the end of the connection. */
    private fun readRequest(input: InputStream): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) throw IOException("Invalid request")
        val headers = HashMap<String, String>()
        var size = requestLine.length
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            size += line.length
            if (size > MAX_HEADER_BYTES) throw IOException("Header too large")
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        val http11 = parts[2] == "HTTP/1.1"
        val connection = headers["connection"]?.lowercase()
        val keepAlive = if (http11) connection != "close" else connection == "keep-alive"
        return Request(parts[0], parts[1].substringBefore('?'), headers, keepAlive)
    }

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (line.isEmpty()) null else line.toString()
            if (byte == '\n'.code) return line.toString().trimEnd('\r')
            line.append(byte.toChar())
            if (line.length > MAX_HEADER_BYTES) throw IOException("Line too long")
        }
    }

    private companion object {
        const val TAG = "HttpFileServer"
        const val MAX_CONNECTIONS = 8
        const val IDLE_TIMEOUT_MS = 30_000
        const val BUFFER_SIZE = 64 * 1024
        const val MAX_HEADER_BYTES = 16 * 1024
    }
}

/** What a `Range` header requests from a file of [length] bytes (RFC 9110, single range only). */
internal sealed interface ByteRange {
    data object Whole : ByteRange
    data class Partial(val start: Long, val endInclusive: Long) : ByteRange
    data object Unsatisfiable : ByteRange
}

/** Multiple ranges or an unintelligible value yield the whole file (the standard allows that). */
internal fun parseByteRange(header: String?, length: Long): ByteRange {
    val spec = header?.trim()?.takeIf { it.startsWith("bytes=", ignoreCase = true) }?.substring(6)?.trim() ?: return ByteRange.Whole
    if (',' in spec) return ByteRange.Whole
    val dash = spec.indexOf('-')
    if (dash < 0) return ByteRange.Whole
    val first = spec.substring(0, dash).trim()
    val last = spec.substring(dash + 1).trim()
    if (first.isEmpty()) {
        // The last n bytes.
        val suffix = last.toLongOrNull() ?: return ByteRange.Whole
        if (suffix <= 0 || length == 0L) return ByteRange.Unsatisfiable
        return ByteRange.Partial(maxOf(0, length - suffix), length - 1)
    }
    val start = first.toLongOrNull() ?: return ByteRange.Whole
    val end = if (last.isEmpty()) length - 1 else last.toLongOrNull()?.coerceAtMost(length - 1) ?: return ByteRange.Whole
    if (start >= length) return ByteRange.Unsatisfiable
    if (end < start) return ByteRange.Whole
    return ByteRange.Partial(start, end)
}
