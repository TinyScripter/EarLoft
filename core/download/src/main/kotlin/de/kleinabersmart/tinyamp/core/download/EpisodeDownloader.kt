package de.kleinabersmart.tinyamp.core.download

import android.net.Network
import android.util.Log
import de.kleinabersmart.tinyamp.core.database.dao.PodcastDao
import de.kleinabersmart.tinyamp.core.database.entity.BookTrackEntity
import de.kleinabersmart.tinyamp.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

/**
 * Lädt die Audiodatei einer Podcast-Folge vollständig in den App-Speicher (Konzept 12). Wie beim [Downloader] entsteht
 * die Datei als `.part` und wird erst nach vollständigem Empfang umbenannt. Die Adresse steht im Track der Folge; sie
 * führt zu beliebigen Hosts, daher kein Server-Login.
 */
class EpisodeDownloader @Inject constructor(
    private val dao: PodcastDao,
    private val api: PodcastFeedApi,
    private val client: OkHttpClient,
    private val storage: EpisodeStorage,
) {
    /**
     * Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING`; der Aufrufer reiht ihn neu ein oder markiert ihn.
     * Mit [network] läuft die Verbindung über genau dieses Netz (die Uhr lädt über WLAN, wie beim [Downloader]).
     */
    suspend fun download(
        track: BookTrackEntity,
        network: Network? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val bookId = track.bookId
        if (dao.markDownloadRunning(bookId) == 0) return DownloadOutcome.Cancelled
        return try {
            fetch(track, network, onProgress).also { outcome ->
                if (outcome is DownloadOutcome.Failed) dao.markDownloadFailed(bookId)
                if (outcome !is DownloadOutcome.Done) storage.partFileFor(bookId).delete()
            }
        } catch (e: CancellationException) {
            storage.partFileFor(bookId).delete()
            withContext(NonCancellable) { dao.requeueDownload(bookId) }
            throw e
        } catch (e: IOException) {
            storage.partFileFor(bookId).delete()
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(track: BookTrackEntity, network: Network?, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        val request = api.audioRequest(track.remoteFileId) ?: return DownloadOutcome.Failed("Ungültige Adresse")
        val call = clientFor(network).newCall(request)
        val cancelCall = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        try {
            return withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    when {
                        response.code in 500..599 -> throw IOException("HTTP ${response.code}")
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
                    writeToPart(track.bookId, response.body.byteStream(), total, onProgress)
                    val mimeType = contentType.takeIf { it.startsWith("audio/") } ?: track.mimeType
                    finish(track.bookId, mimeType)
                }
            }
        } finally {
            cancelCall.dispose()
        }
    }

    private fun clientFor(network: Network?): OkHttpClient =
        if (network == null) {
            client
        } else {
            client.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .build()
        }

    private suspend fun writeToPart(bookId: Long, input: java.io.InputStream, total: Long?, onProgress: suspend (Long, Long?) -> Unit) {
        storage.ensureDirectory()
        var written = 0L
        var lastReport = 0L
        storage.partFileFor(bookId).outputStream().use { output ->
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

    private suspend fun finish(bookId: Long, mimeType: String?): DownloadOutcome {
        val part = storage.partFileFor(bookId)
        val target = storage.fileFor(bookId, mimeType)
        if (!part.renameTo(target)) throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        val size = target.length()
        if (dao.markDownloadDone(bookId, target.absolutePath, size) == 0) {
            // Während des Ladens entfernt oder zurückgesetzt.
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Folge $bookId geladen ($size Bytes)")
        return DownloadOutcome.Done
    }

    private companion object {
        const val TAG = "EpisodeDownloader"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 500_000_000L
        const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024
    }
}
