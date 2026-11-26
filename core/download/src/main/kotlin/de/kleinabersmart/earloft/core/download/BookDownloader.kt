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
 * Downloads one file of an audiobook completely into app storage (concept 12). The transfer itself, including
 * resuming after an interruption, is done by [ResumableTransfer]; the `.part` file is only renamed once fully
 * received. Authentication is added by the shared client's `AbsAuthInterceptor` (bearer token) once the server
 * is loaded.
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
     * On [DownloadOutcome.Retry] the entry stays `RUNNING` and the partial file is kept, so the next attempt
     * continues there; the caller re-enqueues or marks it. With [network] the connection runs over exactly
     * this network (the watch downloads over Wi-Fi, Wear concept 16.2).
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
                // Only this file: the others keep downloading, the book is then partially downloaded.
                if (outcome is DownloadOutcome.Failed) dao.markBookTrackFailed(bookId, index)
                if (outcome is DownloadOutcome.Failed || outcome is DownloadOutcome.Cancelled) storage.deletePart(bookId, index)
            }
        } catch (e: CancellationException) {
            // Aborted (e.g. network gone, worker stopped): the partial file stays for the next run.
            withContext(NonCancellable) { dao.requeueBookTrack(bookId, index) }
            throw e
        } catch (e: IOException) {
            if (e.isNoSpace()) {
                storage.deletePart(bookId, index)
                DownloadOutcome.NoSpace(totalBytes = null)
            } else {
                DownloadOutcome.Retry(e)
            }
        }
    }

    private suspend fun fetch(track: BookTrackEntity, network: Network?, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        val book = dao.book(track.bookId) ?: return DownloadOutcome.Cancelled
        // Books from ARD Sounds: the file ID is the file's URL (`books_improvements.md` M9).
        val url = if (isArdSoundsBook(book.serverId)) {
            track.remoteFileId.toHttpUrlOrNull() ?: return DownloadOutcome.Failed("No address")
        } else {
            val server = servers.server(book.serverId) ?: return DownloadOutcome.Failed("Server not set up")
            api.fileUrl(server, book.remoteId, track.remoteFileId)
        }
        val transfer = if (network == null) transfer else ResumableTransfer(clientFor(network), storage)
        // "Remove download" resets the state; without this check the transfer would keep running to the end.
        return when (val result = transfer.run(url, track.bookId, track.trackIndex, onProgress, { stillRunning(track) })) {
            is TransferResult.Failed -> {
                // If the server denies access, that applies to every file of the book: don't request each one individually.
                if (result.forbidden) dao.markBookDownloadFailed(track.bookId)
                DownloadOutcome.Failed(result.reason)
            }
            TransferResult.Cancelled -> DownloadOutcome.Cancelled
            is TransferResult.NoSpace -> DownloadOutcome.NoSpace(result.totalBytes)
            is TransferResult.Completed -> finish(track, result.mimeType ?: track.mimeType)
        }
    }

    private suspend fun stillRunning(track: BookTrackEntity): Boolean =
        dao.tracks(track.bookId).getOrNull(track.trackIndex)?.downloadState == TrackDownload.RUNNING

    private suspend fun finish(track: BookTrackEntity, mimeType: String?): DownloadOutcome {
        val part = storage.partFileFor(track.bookId, track.trackIndex)
        val target = storage.freeFileFor(track.bookId, track.trackIndex, mimeType, dao.otherTrackFiles(track.bookId, track.trackIndex))
        if (!part.renameTo(target)) {
            // Removed shortly before the end: the partial file is already gone, no reason for a new attempt.
            if (!stillRunning(track)) return DownloadOutcome.Cancelled
            throw DeviceStorageException("Renaming to ${target.name} failed")
        }
        val size = target.length()
        if (dao.markBookTrackDone(track.bookId, track.trackIndex, target.absolutePath, size) == 0) {
            // Removed or reset during the download.
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Book ${track.bookId} file ${track.trackIndex} downloaded ($size bytes)")
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
