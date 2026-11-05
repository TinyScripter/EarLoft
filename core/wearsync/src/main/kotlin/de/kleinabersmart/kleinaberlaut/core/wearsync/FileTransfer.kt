package de.kleinabersmart.kleinaberlaut.core.wearsync

import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.ChannelClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
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
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "WearFileTransfer"
private const val BUFFER_BYTES = 64 * 1024

/** Wie lange die Uhr auf die Kopfzeile des Handys wartet (Handy außer Reichweite, App nicht startbar). */
private const val HEADER_TIMEOUT_MS = 30_000L

/** Wie lange während der Übertragung kein Byte ankommen darf (Bluetooth weg): Danach bricht die Uhr ab und versucht es später erneut. */
private const val STALL_TIMEOUT_MS = 30_000L

private const val WATCHDOG_INTERVAL_MS = 5_000L
private const val NANOS_PER_MS = 1_000_000L

/** Kopfzeile des Datei-Kanals: die Länge in Bytes, [NO_FILE] wenn das Handy keine Datei hat. */
const val NO_FILE = -1L

/** Ergebnis von [receiveFile]. */
sealed interface FileReceive {
    data class Received(val bytes: Long) : FileReceive

    /** Das Handy hat zu dieser Quelle keine Datei (weder lokal noch als Download). */
    data object Missing : FileReceive
}

/**
 * Uhr: Öffnet `/kal/file/<sourceUid>` zum Handy und schreibt die Datei in [output]. Das Handy sendet erst
 * die Länge (`Long`), dann genau so viele Bytes; kürzer ist ein Fehler. Gelesen wird in Blöcken, [onProgress]
 * meldet den Stand nach jedem. Übertragungsfehler (auch ein nicht erreichbares Handy) sind [IOException]s.
 */
suspend fun ChannelClient.receiveFile(
    nodeId: String,
    sourceUid: String,
    output: OutputStream,
    onProgress: suspend (bytesDone: Long, bytesTotal: Long) -> Unit = { _, _ -> },
): FileReceive {
    val channel = try {
        openChannel(nodeId, WearPaths.fileChannel(sourceUid)).await()
    } catch (e: ApiException) {
        throw IOException("Kanal zum Handy nicht geöffnet (${e.statusCode})", e)
    }
    return coroutineScope {
        val headerRead = AtomicBoolean(false)
        val timedOut = AtomicBoolean(false)
        val lastActivity = AtomicLong(System.nanoTime())
        // Blockierende Lesezugriffe lassen sich nur durch Schließen des Kanals beenden: bei Abbruch von außen,
        // wenn die Kopfzeile ausbleibt und wenn die Übertragung stockt (Bluetooth-Verbindung weg).
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
                headerRead.set(true)
                if (length == NO_FILE) return@withContext FileReceive.Missing
                copyExactly(input, output, length) { done, total ->
                    lastActivity.set(System.nanoTime())
                    onProgress(done, total)
                }
                FileReceive.Received(length)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            throw IOException("Kanal zum Handy nicht lesbar (${e.statusCode})", e)
        } catch (e: IOException) {
            throw if (timedOut.get()) IOException("Handy antwortet nicht", e) else e
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
        if (read < 0) throw EOFException("Unvollständig: $done von $length Bytes")
        output.write(buffer, 0, read)
        done += read
        onProgress(done, length)
    }
}

/**
 * Handy: Schreibt die Datei in den von der Uhr geöffneten [channel] (Länge, dann Inhalt) und schließt ihn.
 * Mit `source == null` geht nur [NO_FILE] raus. [source] wird immer geschlossen.
 */
suspend fun ChannelClient.sendFile(channel: ChannelClient.Channel, length: Long, source: InputStream?) {
    try {
        withContext(Dispatchers.IO) {
            DataOutputStream(getOutputStream(channel).await().buffered(BUFFER_BYTES)).use { output ->
                output.writeLong(if (source == null) NO_FILE else length)
                if (source != null) {
                    val written = source.use { copyAll(it, output) }
                    if (written != length) Log.w(TAG, "Länge angekündigt $length, gesendet $written")
                }
            }
        }
    } catch (e: ApiException) {
        Log.w(TAG, "Kanal zur Uhr nicht beschreibbar (${e.statusCode})", e)
    } catch (e: IOException) {
        Log.w(TAG, "Übertragung zur Uhr abgebrochen", e)
    } finally {
        source?.close()
        try {
            close(channel).await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Kanal schon geschlossen", e)
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
