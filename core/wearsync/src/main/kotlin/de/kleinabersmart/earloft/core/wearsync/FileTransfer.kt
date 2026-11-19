package de.kleinabersmart.earloft.core.wearsync

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WearFileTransfer"
private const val BUFFER_BYTES = 64 * 1024

/** How long the watch waits for the phone's header (phone out of range, app can't be started). */
private const val HEADER_TIMEOUT_MS = 30_000L

/** How long no byte may arrive during the transfer (Bluetooth gone): after that the watch aborts and retries later. */
private const val STALL_TIMEOUT_MS = 30_000L

private const val WATCHDOG_INTERVAL_MS = 5_000L
private const val NANOS_PER_MS = 1_000_000L

/** Header of the file channel: the length in bytes, [NO_FILE] if the phone has no file. */
const val NO_FILE = -1L

/** Result of [receiveFile]. */
sealed interface FileReceive {
    data class Received(val bytes: Long) : FileReceive

    /** The phone has no file for this source (neither local nor as a download). */
    data object Missing : FileReceive
}

/**
 * Watch: opens `/earloft/file/<sourceUid>` to the phone and writes the file to [output]. The phone first sends
 * the length (`Long`), then exactly that many bytes; shorter is an error. Reads in blocks, [onProgress]
 * reports the progress after each. Transfer errors (including an unreachable phone) are [IOException]s.
 */
suspend fun ChannelClient.receiveFile(
    nodeId: String,
    sourceUid: String,
    output: OutputStream,
    onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
): FileReceive = receive(nodeId, sourceUid, from = 0, open = { output }, onProgress)

/**
 * Watch: like [receiveFile], but into the partial file [part] and resumable (Wear concept 8.4). [lengthFile] holds the
 * total length the phone named on the first attempt. If a matching partial file exists, the watch only requests the rest
 * ([WearPaths.FILE_FROM_CHANNEL_PREFIX]); if the phone then names a different length (file changed), it starts over
 * immediately. On an abort both files are kept so the next attempt can continue; after complete
 * reception [lengthFile] disappears, and the caller renames [part]. If the phone has no file, both disappear.
 */
suspend fun ChannelClient.receiveFileResumable(
    nodeId: String,
    sourceUid: String,
    part: File,
    lengthFile: File,
    onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
): FileReceive {
    val from = withContext(Dispatchers.IO) { resumeOffset(part, lengthFile) }
    if (from > 0) Log.i(TAG, "$sourceUid: resuming at $from bytes")
    return try {
        receiveInto(nodeId, sourceUid, part, lengthFile, from, onProgress)
    } catch (_: LengthChanged) {
        Log.i(TAG, "$sourceUid changed on the phone, starting over")
        receiveInto(nodeId, sourceUid, part, lengthFile, from = 0, onProgress)
    }
}

/** Where an existing partial file can continue; `0` if there is no usable one. */
internal fun resumeOffset(part: File, lengthFile: File): Long {
    if (!part.isFile || !lengthFile.isFile) return 0
    val total = readLength(lengthFile) ?: return 0
    val done = part.length()
    // Empty or already as long as the file: nothing to resume from.
    return if (done in 1 until total) done else 0
}

private fun readLength(lengthFile: File): Long? =
    try {
        lengthFile.readText().trim().toLongOrNull()
    } catch (_: IOException) {
        null
    }

private suspend fun ChannelClient.receiveInto(
    nodeId: String,
    sourceUid: String,
    part: File,
    lengthFile: File,
    from: Long,
    onProgress: suspend (Long, Long) -> Unit,
): FileReceive {
    var output: OutputStream? = null
    try {
        val result = receive(nodeId, sourceUid, from, open = { total ->
            if (from > 0 && readLength(lengthFile) != total) throw LengthChanged()
            if (from == 0L) {
                part.parentFile?.mkdirs()
                lengthFile.writeText(total.toString())
            }
            FileOutputStream(part, /* append = */ from > 0).also { output = it }
        }, onProgress)
        withContext(Dispatchers.IO) {
            when (result) {
                is FileReceive.Received -> lengthFile.delete()
                FileReceive.Missing -> {
                    part.delete()
                    lengthFile.delete()
                }
            }
        }
        return result
    } finally {
        withContext(NonCancellable + Dispatchers.IO) { output?.close() }
    }
}

/** When resuming, the phone names a different total length than on the first attempt. */
private class LengthChanged : Exception()

/**
 * Opens the channel from byte [from], reads the header (total length) and writes the remaining bytes into the stream that
 * [open] returns for that length (on `Dispatchers.IO`). [onProgress] counts across the whole file.
 */
private suspend fun ChannelClient.receive(
    nodeId: String,
    sourceUid: String,
    from: Long,
    open: (total: Long) -> OutputStream,
    onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit,
): FileReceive {
    val channel = try {
        openChannel(nodeId, WearPaths.fileChannel(sourceUid, from)).await()
    } catch (e: ApiException) {
        throw IOException("Channel to the phone not opened (${e.statusCode})", e)
    }
    return coroutineScope {
        val headerRead = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val lastActivity = AtomicLong(System.nanoTime())
        // Blocking reads can only be ended by closing the channel: on external cancellation,
        // when the header doesn't arrive and when the transfer stalls (Bluetooth connection gone).
        val guard = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                while (true) {
                    delay(WATCHDOG_INTERVAL_MS)
                    val idleMs = (System.nanoTime() - lastActivity.get()) / NANOS_PER_MS
                    val limitMs = if (headerRead.get()) STALL_TIMEOUT_MS else HEADER_TIMEOUT_MS
                    if (idleMs > limitMs) {
                        timedOut.set(true)
                        close(channel)
                        break
                    }
                }
                awaitCancellation()
            } finally {
                close(channel)
            }
        }
        try {
            withContext(Dispatchers.IO) {
                val input = DataInputStream(getInputStream(channel).await().buffered(BUFFER_BYTES))
                val length = input.readLong()
                // The deadline for a stalled transfer only starts now; the wait for the header doesn't count.
                lastActivity.set(System.nanoTime())
                headerRead.set(true)
                if (length == NO_FILE) return@withContext FileReceive.Missing
                if (from > 0 && from >= length) throw LengthChanged()
                val output = open(length)
                lastActivity.set(System.nanoTime())
                copyExactly(input, output, length - from) { done, _ ->
                    lastActivity.set(System.nanoTime())
                    onProgress(from + done, length)
                }
                FileReceive.Received(length)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw IOException("Channel to the phone not readable (${e.statusCode})", e)
        } catch (e: IOException) {
            throw if (timedOut.get()) IOException("Phone not responding", e) else e
        } finally {
            guard.cancel()
        }
    }
}

private suspend fun copyExactly(
    input: InputStream,
    output: OutputStream,
    length: Long,
    onProgress: suspend (Long, Long) -> Unit,
) {
    val buffer = ByteArray(BUFFER_BYTES)
    var done = 0L
    while (done < length) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), length - done).toInt())
        if (read < 0) throw EOFException("Incomplete: $done of $length bytes")
        output.write(buffer, 0, read)
        done += read
        onProgress(done, length)
    }
}

/**
 * Phone: writes the file into the [channel] opened by the watch (full length, then the content from byte [from], see
 * [WearPaths.FILE_FROM_CHANNEL_PREFIX]) and closes it. With `source == null` only [NO_FILE] goes out. [source] is always
 * closed.
 */
suspend fun ChannelClient.sendFile(channel: ChannelClient.Channel, length: Long, source: InputStream?, from: Long = 0) {
    try {
        withContext(Dispatchers.IO) {
            DataOutputStream(getOutputStream(channel).await().buffered(BUFFER_BYTES)).use { output ->
                output.writeLong(if (source == null) NO_FILE else length)
                // If the offset lies beyond the end, the watch aborts after the header and starts over.
                if (source != null && from < length) {
                    val written = source.use {
                        skipFully(it, from)
                        copyAll(it, output)
                    }
                    if (written != length - from) Log.w(TAG, "Announced length $length from $from, sent $written")
                }
            }
        }
    } catch (e: ApiException) {
        Log.w(TAG, "Channel to the watch not writable (${e.statusCode})", e)
    } catch (e: IOException) {
        Log.w(TAG, "Transfer to the watch aborted", e)
    } finally {
        source?.close()
        try {
            close(channel).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Channel already closed", e)
        }
    }
}

/** `InputStream.skip` may skip less than requested; content URIs do that too. */
private fun skipFully(input: InputStream, count: Long) {
    var left = count
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            if (input.read() < 0) throw EOFException("File ends before byte $count")
            left--
        }
    }
}

private suspend fun copyAll(input: InputStream, output: OutputStream): Long {
    val buffer = ByteArray(BUFFER_BYTES)
    var total = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read < 0) return total
        output.write(buffer, 0, read)
        total += read
    }
}
