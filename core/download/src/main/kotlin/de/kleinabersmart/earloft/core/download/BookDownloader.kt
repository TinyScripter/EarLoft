package de.kleinabersmart.earloft.core.download

import android.net.Network
import android.util.Log
import de.kleinabersmart.earloft.core.data.spoken.AudiobookshelfServers
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.model.TrackDownload
import de.kleinabersmart.earloft.core.model.isArdSoundsBook
import de.kleinabersmart.earloft.core.network.audiobookshelf.AbsApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.io.IOException
import javax.inject.Inject

/**
 * Lädt eine Datei eines Hörbuchs vollständig in den App-Speicher (Konzept 12). Die Übertragung selbst, auch das
 * Fortsetzen nach einem Abbruch, macht [ResumableTransfer]; erst nach vollständigem Empfang wird die `.part`-Datei
 * umbenannt. Die Anmeldung setzt der `AbsAuthInterceptor` des geteilten Clients (Bearer-Schlüssel), sobald der Server
 * geladen ist.
 */
class BookDownloader @Inject constructor(
    private val dao: BookDao,
    private val servers: AudiobookshelfServers,
    private val api: AbsApi,
    private val client: OkHttpClient,
    private val storage: BookStorage,
) {
    private val transfer = ResumableTransfer(client, storage)

    /**
     * Bei [DownloadOutcome.Retry] bleibt der Eintrag auf `RUNNING` und die Teildatei liegen, damit der nächste Versuch
     * dort weitermacht; der Aufrufer reiht ihn neu ein oder markiert ihn. Mit [network] läuft die Verbindung über genau
     * dieses Netz (die Uhr lädt über WLAN, Wear-Konzept 16.2).
     */
    suspend fun download(
        track: BookTrackEntity,
        network: Network? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val bookId = track.bookId
        val index = track.trackIndex
        if (dao.markBookTrackRunning(bookId, index) == 0) return DownloadOutcome.Cancelled
        return try {
            fetch(track, network, onProgress).also { outcome ->
                // Nur diese Datei: Die übrigen laden weiter, das Buch ist dann teilweise geladen.
                if (outcome is DownloadOutcome.Failed) dao.markBookTrackFailed(bookId, index)
                if (outcome is DownloadOutcome.Failed || outcome is DownloadOutcome.Cancelled) storage.deletePart(bookId, index)
            }
        } catch (e: CancellationException) {
            // Abgebrochen (z. B. Netz weg, Worker gestoppt): die Teildatei bleibt für den nächsten Lauf.
            withContext(NonCancellable) { dao.requeueBookTrack(bookId, index) }
            throw e
        } catch (e: IOException) {
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(track: BookTrackEntity, network: Network?, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        val book = dao.book(track.bookId) ?: return DownloadOutcome.Cancelled
        // Bücher aus ARD Sounds: Die Dateikennung ist die Adresse der Datei (`books_improvements.md` M9).
        val url = if (isArdSoundsBook(book.serverId)) {
            track.remoteFileId.toHttpUrlOrNull() ?: return DownloadOutcome.Failed("Keine Adresse")
        } else {
            val server = servers.server(book.serverId) ?: return DownloadOutcome.Failed("Server nicht eingerichtet")
            api.fileUrl(server, book.remoteId, track.remoteFileId)
        }
        val transfer = if (network == null) transfer else ResumableTransfer(clientFor(network), storage)
        // „Download entfernen“ setzt den Zustand zurück; ohne die Frage liefe die Übertragung bis zum Ende weiter.
        return when (val result = transfer.run(url, track.bookId, track.trackIndex, onProgress, { stillRunning(track) })) {
            is TransferResult.Failed -> {
                // Verweigert der Server den Zugriff, gilt das für jede Datei des Buchs: nicht jede einzeln anfragen.
                if (result.forbidden) dao.markBookDownloadFailed(track.bookId)
                DownloadOutcome.Failed(result.reason)
            }
            TransferResult.Cancelled -> DownloadOutcome.Cancelled
            is TransferResult.Completed -> finish(track, result.mimeType ?: track.mimeType)
        }
    }

    private suspend fun stillRunning(track: BookTrackEntity): Boolean =
        dao.tracks(track.bookId).getOrNull(track.trackIndex)?.downloadState == TrackDownload.RUNNING

    private suspend fun finish(track: BookTrackEntity, mimeType: String?): DownloadOutcome {
        val part = storage.partFileFor(track.bookId, track.trackIndex)
        val target = storage.freeFileFor(track.bookId, track.trackIndex, mimeType, dao.otherTrackFiles(track.bookId, track.trackIndex))
        if (!part.renameTo(target)) {
            // Kurz vor dem Ende entfernt: Die Teildatei ist schon weg, kein Grund für einen neuen Versuch.
            if (!stillRunning(track)) return DownloadOutcome.Cancelled
            throw IOException("Umbenennen nach ${target.name} fehlgeschlagen")
        }
        val size = target.length()
        if (dao.markBookTrackDone(track.bookId, track.trackIndex, target.absolutePath, size) == 0) {
            // Während des Ladens entfernt oder zurückgesetzt.
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Buch ${track.bookId} Datei ${track.trackIndex} geladen ($size Bytes)")
        return DownloadOutcome.Done
    }

    private fun clientFor(network: Network): OkHttpClient =
        client.newBuilder()
            .socketFactory(network.socketFactory)
            .dns { host -> network.getAllByName(host).toList() }
            .build()

    private companion object {
        const val TAG = "BookDownloader"
    }
}
