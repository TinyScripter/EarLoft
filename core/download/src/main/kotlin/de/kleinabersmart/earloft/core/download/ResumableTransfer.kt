package de.kleinabersmart.earloft.core.download

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import de.kleinabersmart.earloft.core.network.HttpStatusException
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/** Ergebnis von [ResumableTransfer.run]. */
internal sealed interface TransferResult {
    /** Die Datei liegt vollständig als `.part` vor. [mimeType] nur, wenn der Server einen Audio-Typ nannte. */
    data class Completed(val mimeType: String?) : TransferResult

    /** Dauerhaft fehlgeschlagen; die Teildatei ist verworfen. [forbidden]: Der Server verweigert den Zugriff (403). */
    data class Failed(val reason: String, val forbidden: Boolean = false) : TransferResult

    /** Unterwegs nicht mehr gewünscht (Download entfernt); die Teildatei verwirft der Aufrufer. */
    data object Cancelled : TransferResult
}

/**
 * Lädt eine Datei in ihre `.part`-Datei und macht dort weiter, wo ein früherer Versuch aufhörte.
 *
 * Fortsetzen geht nur mit einem **Merkmal** der Serverdatei (starker ETag, sonst Last-Modified) und ihrer Gesamtgröße,
 * die beim ersten Versuch neben die Teildatei geschrieben werden. Die Anfrage trägt `Range` und `If-Range`: Hat sich die
 * Datei auf dem Server geändert, antwortet er mit 200 und der ganzen Datei, und die alte Teildatei wird überschrieben.
 * Eine Antwort mit 206, deren Bereich nicht zur Teildatei passt, würde sie beschädigen und wird verworfen.
 *
 * Netzfehler werden als [IOException] weitergereicht; die Teildatei bleibt dann liegen.
 */
internal class ResumableTransfer(
    private val client: OkHttpClient,
    private val storage: PartFiles,
) {
    suspend fun run(
        url: HttpUrl,
        bookId: Long,
        trackIndex: Int,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit,
        /** Im Takt des Fortschritts gefragt; `false` bricht ab (Download unterwegs entfernt). */
        stillWanted: suspend () -> Boolean = { true },
    ): TransferResult = run(Request.Builder().url(url).build(), bookId, trackIndex, onProgress, stillWanted)

    /** Wie oben; [base] trägt eigene Kopfzeilen (Podcast-Feeds: User-Agent). */
    suspend fun run(
        base: Request,
        bookId: Long,
        trackIndex: Int,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit,
        stillWanted: suspend () -> Boolean = { true },
    ): TransferResult {
        // Vor dem Merkmal: Beim allerersten Buch auf dem Gerät (z. B. auf der Uhr) gibt es den Ordner noch nicht.
        storage.ensureDirectory()
        val part = storage.partFileFor(bookId, trackIndex)
        val meta = storage.metaFileFor(bookId, trackIndex)
        val resume = readResume(part, meta)
        if (resume == null) storage.deletePart(bookId, trackIndex)

        val request = base.newBuilder().apply {
            if (resume != null) {
                header("Range", "bytes=${resume.offset}-")
                header("If-Range", resume.validator)
            }
        }.build()
        val call = client.newCall(request)
        val cancelCall = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    when {
                        response.code == 416 -> {
                            // Die Teildatei passt nicht mehr zur Serverdatei: von vorn beginnen.
                            storage.deletePart(bookId, trackIndex)
                            throw IOException("Bereich nicht erfüllbar, beginne von vorn")
                        }
                        response.code in 500..599 -> throw IOException("HTTP ${response.code}")
                        // 401 ist ein Netzfehler im Sinne des Aufrufers: Auf der Uhr kann ein neuer Schlüssel vom Handy kommen.
                        response.code == 401 -> throw HttpStatusException(response.code)
                        response.code == 403 -> return@withContext TransferResult.Failed("Nicht angemeldet", forbidden = true)
                        !response.isSuccessful -> return@withContext TransferResult.Failed("HTTP ${response.code}")
                    }
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                        storage.deletePart(bookId, trackIndex)
                        return@withContext TransferResult.Failed("Keine Audiodatei ($contentType)")
                    }

                    val length = response.body.contentLength().takeIf { it > 0 }
                    val offset: Long
                    val total: Long?
                    if (response.code == 206) {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (resume == null || range == null || range.start != resume.offset) {
                            storage.deletePart(bookId, trackIndex)
                            throw IOException("Unerwarteter Bereich: ${response.header("Content-Range")}")
                        }
                        offset = resume.offset
                        total = range.total ?: length?.let { offset + it }
                        Log.i(TAG, "Eintrag $bookId Datei $trackIndex: setze bei $offset von ${total ?: "?"} Bytes fort")
                    } else {
                        // 200: ganze Datei, entweder frischer Start oder der Server hat die Datei geändert.
                        offset = 0
                        total = length
                        rememberValidator(response.header("ETag"), response.header("Last-Modified"), total, meta)
                    }

                    val missing = total?.let { it - offset }
                    if (missing != null && storage.freeBytes() < missing + FREE_SPACE_RESERVE_BYTES) {
                        storage.deletePart(bookId, trackIndex)
                        return@withContext TransferResult.Failed("Zu wenig Speicher")
                    }
                    if (!write(part, response.body.byteStream(), append = offset > 0, offset, total, onProgress, stillWanted)) {
                        return@withContext TransferResult.Cancelled
                    }
                    meta.delete()
                    TransferResult.Completed(contentType.takeIf { it.startsWith("audio/") })
                }
            }
        } finally {
            cancelCall.dispose()
        }
    }

    /** Offset und Merkmal einer brauchbaren Teildatei; `null`, wenn nichts da ist oder es nicht fortgesetzt werden kann. */
    private fun readResume(part: File, meta: File): Resume? {
        if (!part.isFile || !meta.isFile) return null
        val lines = runCatching { meta.readLines() }.getOrNull() ?: return null
        val validator = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val total = lines.getOrNull(1)?.toLongOrNull() ?: return null
        val offset = part.length()
        // Leer oder schon so lang wie die Datei: nichts, woran man anknüpfen könnte.
        return if (offset in 1 until total) Resume(offset, validator) else null
    }

    /** Merkt sich Merkmal und Größe; ohne beides lässt sich der Versuch später nicht sicher fortsetzen. */
    private fun rememberValidator(etag: String?, lastModified: String?, total: Long?, meta: File) {
        val validator = etag?.takeIf { !it.startsWith("W/") } ?: lastModified
        if (validator == null || total == null) {
            meta.delete()
            return
        }
        meta.writeText("$validator\n$total\n")
    }

    private suspend fun write(
        part: File,
        input: InputStream,
        append: Boolean,
        offset: Long,
        total: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
        stillWanted: suspend () -> Boolean,
    ): Boolean {
        storage.ensureDirectory()
        var written = offset
        var lastReport = 0L
        FileOutputStream(part, append).use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
                written += read
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    if (!stillWanted()) return false
                    onProgress(written, total)
                }
            }
        }
        if (total != null && written != total) throw IOException("Unvollständig: $written von $total Bytes")
        return true
    }

    private class Resume(val offset: Long, val validator: String)

    internal class ContentRange(val start: Long, val total: Long?)

    companion object {
        private const val TAG = "ResumableTransfer"
        private const val BUFFER_BYTES = 64 * 1024
        private const val PROGRESS_INTERVAL_NANOS = 500_000_000L
        private const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024

        private val CONTENT_RANGE = Regex("""bytes (\d+)-\d+/(\d+|\*)""")

        /** `bytes 100-999/1000` → Anfang 100, Gesamtgröße 1000 (`*` = unbekannt). */
        internal fun parseContentRange(header: String?): ContentRange? {
            val match = header?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return null
            return ContentRange(match.groupValues[1].toLong(), match.groupValues[2].toLongOrNull())
        }
    }
}
