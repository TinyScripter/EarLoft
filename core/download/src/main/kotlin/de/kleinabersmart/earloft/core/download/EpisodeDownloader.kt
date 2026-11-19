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
 * Downloads the audio file of a podcast episode completely into app storage (concept 12). The transfer, including
 * resuming after an interruption, is done by [ResumableTransfer] as for books; the `.part` file is only renamed once
 * fully received. The URL is in the episode's track; it leads to arbitrary hosts, hence no server login.
 * Only the credentials of a protected subscription are sent along, if the file is on its host (concept 12.10).
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
     * On [DownloadOutcome.Retry] the entry stays `RUNNING` and the partial file is kept, so the next attempt continues
     * there; the caller re-enqueues or marks it. With [network] the connection runs over exactly this
     * network (the watch downloads over Wi-Fi, as with the [Downloader]).
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
            // Aborted (e.g. network gone, worker stopped): the partial file stays for the next run.
            withContext(NonCancellable) { dao.requeueDownload(bookId) }
            throw e
        } catch (e: IOException) {
            DownloadOutcome.Retry(e)
        }
    }

    private suspend fun fetch(track: BookTrackEntity, network: Network?, onProgress: suspend (Long, Long?) -> Unit): DownloadOutcome {
        // The process may have been started for the download before the credentials of all subscriptions are available.
        val auth = dao.feedIdOf(track.bookId)?.let { credentials.forFeed(it) }
        val request = api.audioRequest(track.remoteFileId, auth) ?: return DownloadOutcome.Failed("Invalid address")
        val transfer = if (network == null) transfer else ResumableTransfer(clientFor(network), storage)
        // "Remove download" resets the state; without this check the transfer would keep running to the end.
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
            // Removed shortly before the end: the partial file is already gone, no reason for a new attempt.
            if (!stillRunning(bookId)) return DownloadOutcome.Cancelled
            throw IOException("Renaming to ${target.name} failed")
        }
        val size = target.length()
        if (dao.markDownloadDone(bookId, target.absolutePath, size) == 0) {
            // Removed or reset during the download.
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Episode $bookId downloaded ($size bytes)")
        return DownloadOutcome.Done
    }

    private companion object {
        const val TAG = "EpisodeDownloader"
    }
}
