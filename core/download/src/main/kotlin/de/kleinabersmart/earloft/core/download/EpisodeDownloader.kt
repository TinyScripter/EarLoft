package de.kleinabersmart.earloft.core.download

import android.net.Network
import android.util.Log
import de.kleinabersmart.earloft.core.data.podcast.FeedCredentials
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.network.podcast.PodcastFeedApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

/**
 * Lädt die Audiodatei einer Podcast-Folge vollständig in den App-Speicher (Konzept 12). Die Übertragung, auch das
 * Fortsetzen nach einem Abbruch, macht [ResumableTransfer] wie bei den Büchern; erst nach vollständigem Empfang wird die
 * `.part`-Datei umbenannt. Die Adresse steht im Track der Folge; sie führt zu beliebigen Hosts, daher kein Server-Login.
 * Nur die Zugangsdaten eines geschützten Abos gehen mit, wenn die Datei auf dessen Host liegt (Konzept 12.10).
 */
class EpisodeDownloader @Inject constructor(
    private val dao: PodcastDao,
    private val api: PodcastFeedApi,
    private val client: OkHttpClient,
    private val storage: EpisodeStorage,
    private val credentials: FeedCredentials,
) {
    private val transfer = ResumableTransfer(client, storage)

    /**
     * Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING` und die Teildatei liegen, damit der nächste Versuch dort
     * weitermacht; der Aufrufer reiht ihn neu ein oder markiert ihn. Mit [network] läuft die Verbindung über genau dieses
     * Netz (die Uhr lädt über WLAN, wie beim [Downloader]).
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
                if (outcome is DownloadOutcome.Failed || outcome is DownloadOutcome.Cancelled) storage.deletePart(bookId, 0)
            }
        } catch (e: CancellationException) {
            // Abgebrochen (z. B. Netz weg, Worker gestoppt): die Teildatei bleibt für den nächsten Lauf.
            withContext(NonCancellable) { dao.requeueDownload(bookId) }
            throw e
        } catch (e: IOException) {
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(track: BookTrackEntity, network: Network?, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        // Der Prozess kann für den Download gestartet sein, bevor die Zugangsdaten aller Abos bereitliegen.
        val auth = dao.feedIdOf(track.bookId)?.let { credentials.forFeed(it) }
        val request = api.audioRequest(track.remoteFileId, auth) ?: return DownloadOutcome.Failed("Ungültige Adresse")
        val transfer = if (network == null) transfer else ResumableTransfer(clientFor(network), storage)
        // „Download entfernen“ setzt den Zustand zurück; ohne die Frage liefe die Übertragung bis zum Ende weiter.
        return when (val result = transfer.run(request, track.bookId, 0, onProgress, { stillRunning(track.bookId) })) {
            is TransferResult.Failed -> DownloadOutcome.Failed(result.reason)
            TransferResult.Cancelled -> DownloadOutcome.Cancelled
            is TransferResult.Completed -> finish(track.bookId, result.mimeType ?: track.mimeType)
        }
    }

    private fun clientFor(network: Network): OkHttpClient =
        client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .build()

    private suspend fun stillRunning(bookId: Long): Boolean = dao.trackOf(bookId)?.downloadState == TrackDownload.RUNNING

    private suspend fun finish(bookId: Long, mimeType: String?): DownloadOutcome {
        val part = storage.partFileFor(bookId)
        val target = storage.fileFor(bookId, mimeType)
        if (!part.renameTo(target)) {
            // Kurz vor dem Ende entfernt: Die Teildatei ist schon weg, kein Grund für einen neuen Versuch.
            if (!stillRunning(bookId)) return DownloadOutcome.Cancelled
            throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        }
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
    }
}
