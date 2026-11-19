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

/** Result of [ResumableTransfer.run]. */
internal sealed interface TransferResult {
    /** The file is complete as `.part`. [mimeType] only if the server reported an audio type. */
    data class Completed(val mimeType: String?) : TransferResult

    /** Failed permanently; the partial file has been discarded. [forbidden]: the server denies access (403). */
    data class Failed(val reason: String, val forbidden: Boolean = false) : TransferResult

    /** No longer wanted along the way (download removed); the caller discards the partial file. */
    data object Cancelled : TransferResult
}

/**
 * Downloads a file into its `.part` file and continues where an earlier attempt stopped.
 *
 * Resuming only works with a **fingerprint** of the server file (strong ETag, otherwise Last-Modified) and its total size,
 * which are written next to the partial file on the first attempt. The request carries `Range` and `If-Range`: if the
 * file has changed on the server, it answers with 200 and the whole file, and the old partial file is overwritten.
 * A 206 response whose range doesn't match the partial file would corrupt it and is discarded.
 *
 * Network errors are passed on as [IOException]; the partial file is then kept.
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
        /** Asked at the pace of the progress; `false` aborts (download removed along the way). */
        stillWanted: suspend () -> Boolean = { true },
    ): TransferResult = run(Request.Builder().url(url).build(), bookId, trackIndex, onProgress, stillWanted)

    /** As above; [base] carries its own headers (podcast feeds: User-Agent). */
    suspend fun run(
        base: Request,
        bookId: Long,
        trackIndex: Int,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit,
        stillWanted: suspend () -> Boolean = { true },
    ): TransferResult {
        // Before the fingerprint: for the very first book on the device (e.g. on the watch) the folder doesn't exist yet.
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
                            // The partial file no longer matches the server file: start from scratch.
                            storage.deletePart(bookId, trackIndex)
                            throw IOException("Range not satisfiable, starting over")
                        }
                        response.code in 500..599 -> throw IOException("HTTP ${response.code}")
                        // 401 is a network error from the caller's point of view: on the watch a new key may come from the phone.
                        response.code == 401 -> throw HttpStatusException(response.code)
                        response.code == 403 -> return@withContext TransferResult.Failed("Not signed in", forbidden = true)
                        !response.isSuccessful -> return@withContext TransferResult.Failed("HTTP ${response.code}")
                    }
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                        storage.deletePart(bookId, trackIndex)
                        return@withContext TransferResult.Failed("Not an audio file ($contentType)")
                    }

                    val length = response.body.contentLength().takeIf { it > 0 }
                    val offset: Long
                    val total: Long?
                    if (response.code == 206) {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (resume == null || range == null || range.start != resume.offset) {
                            storage.deletePart(bookId, trackIndex)
                            throw IOException("Unexpected range: ${response.header("Content-Range")}")
                        }
                        offset = resume.offset
                        total = range.total ?: length?.let { offset + it }
                        Log.i(TAG, "Entry $bookId file $trackIndex: resuming at $offset of ${total ?: "?"} bytes")
                    } else {
                        // 200: whole file, either a fresh start or the server has changed the file.
                        offset = 0
                        total = length
                        rememberValidator(response.header("ETag"), response.header("Last-Modified"), total, meta)
                    }

                    val missing = total?.let { it - offset }
                    if (missing != null && storage.freeBytes() < missing + FREE_SPACE_RESERVE_BYTES) {
                        storage.deletePart(bookId, trackIndex)
                        return@withContext TransferResult.Failed("Not enough storage")
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

    /** Offset and fingerprint of a usable partial file; `null` if there is none or it can't be resumed. */
    private fun readResume(part: File, meta: File): Resume? {
        if (!part.isFile || !meta.isFile) return null
        val lines = runCatching { meta.readLines() }.getOrNull() ?: return null
        val validator = lines.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val total = lines.getOrNull(1)?.toLongOrNull() ?: return null
        val offset = part.length()
        // Empty or already as long as the file: nothing to resume from.
        return if (offset in 1 until total) Resume(offset, validator) else null
    }

    /** Remembers fingerprint and size; without both the attempt can't be safely resumed later. */
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
        if (total != null && written != total) throw IOException("Incomplete: $written of $total bytes")
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

        /** `bytes 100-999/1000` → start 100, total size 1000 (`*` = unknown). */
        internal fun parseContentRange(header: String?): ContentRange? {
            val match = header?.let { CONTENT_RANGE.matchEntire(it.trim()) } ?: return null
            return ContentRange(match.groupValues[1].toLong(), match.groupValues[2].toLongOrNull())
        }
    }
}
