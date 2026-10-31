package keinabersmart.kleinaberlaut.core.download

import android.util.Log
import keinabersmart.kleinaberlaut.core.data.spoken.AudiobookshelfServers
import keinabersmart.kleinaberlaut.core.database.dao.BookDao
import keinabersmart.kleinaberlaut.core.database.entity.BookTrackEntity
import keinabersmart.kleinaberlaut.core.network.audiobookshelf.AbsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject

/**
 * Lädt eine Datei eines Hörbuchs vollständig in den App-Speicher (Konzept 12). Aufbau wie beim [EpisodeDownloader]:
 * `.part`, erst nach vollständigem Empfang umbenennen. Die Anmeldung setzt der `AbsAuthInterceptor` des geteilten
 * Clients (Bearer-Schlüssel), sobald der Server geladen ist.
 */
class BookDownloader @Inject constructor(
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val client: OkHttpClient,
    private val storage: BookStorage,
) {
    /** Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING`; der Aufrufer reiht ihn neu ein oder markiert ihn. */
    suspend fun download(
        track: BookTrackEntity,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val bookId = track.bookId
        val index = track.trackIndex
        if (dao.markBookTrackRunning(bookId, index) == 0) return DownloadOutcome.Cancelled
        return try {
            fetch(track, onProgress).also { outcome ->
                if (outcome is DownloadOutcome.Failed) dao.markBookDownloadFailed(bookId)
                if (outcome !is DownloadOutcome.Done) storage.partFileFor(bookId, index).delete()
            }
        } catch (e: CancellationException) {
            storage.partFileFor(bookId, index).delete()
            withContext(NonCancellable) { dao.requeueBookTrack(bookId, index) }
            throw e
        } catch (e: IOException) {
            storage.partFileFor(bookId, index).delete()
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(track: BookTrackEntity, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        val book = dao.book(track.bookId) ?: return DownloadOutcome.Cancelled
        val server = servers.server(book.serverId) ?: return DownloadOutcome.Failed("Server nicht eingerichtet")
        val request = Request.Builder().url(api.fileUrl(server, book.remoteId, track.remoteFileId)).build()
        val call = client.newCall(request)
        val cancelCall = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    when {
                        response.code in 500..599 -> throw IOException("HTTP ${response.code}")
                        response.code == 401 || response.code == 403 -> return@withContext DownloadOutcome.Failed("Nicht angemeldet")
                        !response.isSuccessful -> return@withContext DownloadOutcome.Failed("HTTP ${response.code}")
                    }
                    val contentType = response.header("Content-Type").orEmpty().substringBefore(';').trim().lowercase()
                    if (contentType.startsWith("text/") || contentType.contains("json") || contentType.contains("xml")) {
                        return@withContext DownloadOutcome.Failed("Keine Audiodatei ($contentType)")
                    }
                    val total = response.body.contentLength().takeIf { it > 0 }
                    if (total != null && storage.freeBytes() < total + FREE_SPACE_RESERVE_BYTES) {
                        return@withContext DownloadOutcome.Failed("Zu wenig Speicher")
                    }
                    writeToPart(track, response.body.byteStream(), total, onProgress)
                    val mimeType = contentType.takeIf { it.startsWith("audio/") } ?: track.mimeType
                    finish(track, mimeType)
                }
            }
        } finally {
            cancelCall.dispose()
        }
    }

    private suspend fun writeToPart(track: BookTrackEntity, input: InputStream, total: Long?, onProgress: suspend (Long, Long?) -> Unit) {
        storage.ensureDirectory()
        var written = 0L
        var lastReport = 0L
        storage.partFileFor(track.bookId, track.trackIndex).outputStream().use { output ->
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
                    onProgress(written, total)
                }
            }
        }
        if (total != null && written != total) throw IOException("Unvollständig: $written von $total Bytes")
    }

    private suspend fun finish(track: BookTrackEntity, mimeType: String?): DownloadOutcome {
        val part = storage.partFileFor(track.bookId, track.trackIndex)
        val target = storage.fileFor(track.bookId, track.trackIndex, mimeType)
        if (!part.renameTo(target)) throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        val size = target.length()
        if (dao.markBookTrackDone(track.bookId, track.trackIndex, target.absolutePath, size) == 0) {
            // Während des Ladens entfernt oder zurückgesetzt.
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Buch ${track.bookId} Datei ${track.trackIndex} geladen ($size Bytes)")
        return DownloadOutcome.Done
    }

    private companion object {
        const val TAG = "BookDownloader"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 500_000_000L
        const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024
    }
}
