package de.kleinabersmart.earloft.wear.download

import android.util.Log
import de.kleinabersmart.earloft.core.database.dao.BookDao
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.PodcastDao
import de.kleinabersmart.earloft.core.database.entity.BookTrackEntity
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.download.BookStorage
import de.kleinabersmart.earloft.core.download.DownloadOutcome
import de.kleinabersmart.earloft.core.download.DownloadStorage
import de.kleinabersmart.earloft.core.download.EpisodeStorage
import de.kleinabersmart.earloft.core.download.isNoSpace
import de.kleinabersmart.earloft.core.wearsync.FileDoesNotFit
import de.kleinabersmart.earloft.core.wearsync.FileReceive
import de.kleinabersmart.earloft.core.wearsync.WearBookFile
import de.kleinabersmart.earloft.core.wearsync.WearCoverFile
import de.kleinabersmart.earloft.core.wearsync.WearEpisodeFile
import de.kleinabersmart.earloft.core.wearsync.WearThumbnailFile
import de.kleinabersmart.earloft.wear.sync.WearManifestSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches a file in its original form from the phone (Wear concept 8.4): for songs only the phone has, and as a fallback
 * when the server is unreachable; likewise for podcast episodes ([fetchEpisode], 16.1), audiobooks ([fetchBookTrack], 16.2)
 * and their images ([fetchCover]). The file is written as a separate partial file (`.phone.part`) and only renamed once
 * fully received. If the transfer breaks off (phone out of range, app closed on the phone, job stopped), it stays
 * in place and the next attempt resumes there; it is only discarded when the entry goes away.
 */
@Singleton
class PhoneFileFetcher @Inject internal constructor(
    private val downloadDao: DownloadDao,
    private val storage: DownloadStorage,
    private val channel: PhoneChannel,
    private val podcastDao: PodcastDao,
    private val bookDao: BookDao,
    private val episodeStorage: EpisodeStorage,
    private val bookStorage: BookStorage,
) {
    /** Like `Downloader.download`: on [DownloadOutcome.Retry] the entry stays `RUNNING`, the caller decides. */
    suspend fun fetch(
        download: DownloadEntity,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val uid = download.sourceUid
        if (downloadDao.markRunning(uid) == 0) return DownloadOutcome.Cancelled
        val songSource = downloadDao.getSongSource(uid) ?: return DownloadOutcome.Cancelled
        val nodeId = channel.phoneNodeId() ?: return DownloadOutcome.Retry(IOException("Phone not connected"))
        // The phone knows the source by its own `sourceUid`; the watch's pseudo source carries it as `remoteId`.
        val requestUid = if (songSource.sourceId == WearManifestSource.PHONE_SOURCE_ID) songSource.remoteId else uid

        val part = storage.phonePartFileFor(uid)
        val lengthFile = storage.phoneLengthFileFor(uid)
        storage.ensureDirectory()
        return try {
            var lastReport = 0L
            val received = channel.receiveResumable(nodeId, requestUid, part, lengthFile, fits = { fitsForTransfer(it, storage.allocatableBytes()) }) { done, total ->
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    if (downloadDao.updateProgress(uid, done, total) == 0) throw RemovedException()
                    onProgress(done, total)
                }
            }
            when (received) {
                FileReceive.Missing -> {
                    downloadDao.markFailed(uid)
                    DownloadOutcome.Failed(MISSING_ON_PHONE)
                }
                is FileReceive.Received -> finish(uid, songSource.mimeType, received.bytes)
            }
        } catch (e: RemovedException) {
            withContext(NonCancellable + Dispatchers.IO) {
                part.delete()
                lengthFile.delete()
            }
            DownloadOutcome.Cancelled
        } catch (e: CancellationException) {
            withContext(NonCancellable) { downloadDao.requeue(uid) }
            throw e
        } catch (e: IOException) {
            if (e.isNoSpace() || e is FileDoesNotFit) {
                // A full storage is no unreachable phone (review G11): the next attempt would fail the same way.
                Log.w(TAG, "Storage full while receiving $requestUid", e)
                withContext(NonCancellable + Dispatchers.IO) {
                    part.delete()
                    lengthFile.delete()
                }
                // Not failed: without its entry the planner queues the song again once there is room.
                downloadDao.delete(listOf(uid))
                return DownloadOutcome.NoSpace(totalBytes = (e as? FileDoesNotFit)?.totalBytes)
            }
            Log.w(TAG, "Transfer of $requestUid aborted (${part.length()} bytes kept for the next attempt)", e)
            DownloadOutcome.Retry(e)
        }
    }

    /**
     * Fetches the file of a podcast episode from the phone (Wear concept 16.1): episodes from the phone's device and as a
     * fallback when the feed is unreachable. Like [fetch]: on [DownloadOutcome.Retry] the entry stays `RUNNING`.
     */
    suspend fun fetchEpisode(
        track: BookTrackEntity,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val bookId = track.bookId
        // Back to QUEUED: from RUNNING (network error) or FAILED (feed refused).
        podcastDao.requeueDownload(bookId)
        podcastDao.queueDownload(bookId)
        if (podcastDao.markDownloadRunning(bookId) == 0) return DownloadOutcome.Cancelled
        val book = bookDao.book(bookId) ?: return DownloadOutcome.Cancelled
        val nodeId = channel.phoneNodeId() ?: return DownloadOutcome.Retry(IOException("Phone not connected"))
        val requestUid = WearEpisodeFile.uid(book.serverId, book.remoteId)
        val part = episodeStorage.phonePartFileFor(bookId)
        val lengthFile = episodeStorage.phoneLengthFileFor(bookId)
        episodeStorage.ensureDirectory()
        return try {
            var lastReport = 0L
            val received = channel.receiveResumable(
                nodeId,
                requestUid,
                part,
                lengthFile,
                fits = { fitsForTransfer(it, episodeStorage.allocatableBytes()) },
            ) { done, total ->
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    onProgress(done, total)
                }
            }
            when (received) {
                FileReceive.Missing -> {
                    podcastDao.markDownloadFailed(bookId)
                    DownloadOutcome.Failed(MISSING_ON_PHONE)
                }
                is FileReceive.Received -> {
                    val target = episodeStorage.fileFor(bookId, track.mimeType)
                    if (!part.renameTo(target)) throw IOException("Renaming to ${target.name} failed")
                    if (podcastDao.markDownloadDone(bookId, target.absolutePath, target.length()) == 0) {
                        target.delete()
                        DownloadOutcome.Cancelled
                    } else {
                        Log.d(TAG, "Episode loaded from the phone: $requestUid (${received.bytes} bytes)")
                        DownloadOutcome.Done
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { podcastDao.requeueDownload(bookId) }
            throw e
        } catch (e: IOException) {
            if (e.isNoSpace() || e is FileDoesNotFit) {
                // With the length the phone named, the caller remembers the real size (review2 J7).
                Log.w(TAG, "Storage full while receiving $requestUid", e)
                withContext(NonCancellable + Dispatchers.IO) {
                    part.delete()
                    lengthFile.delete()
                }
                bookDao.unqueueTrack(bookId, 0)
                return DownloadOutcome.NoSpace(totalBytes = (e as? FileDoesNotFit)?.totalBytes)
            }
            Log.w(TAG, "Transfer of $requestUid aborted (${part.length()} bytes kept for the next attempt)", e)
            DownloadOutcome.Retry(e)
        }
    }

    /**
     * Fetches a file of an audiobook from the phone (Wear concept 16.2): books from the phone's device and as a fallback when
     * Audiobookshelf is unreachable (then only if the phone has downloaded the book). Like [fetch]: on
     * [DownloadOutcome.Retry] the file stays `RUNNING`.
     */
    suspend fun fetchBookTrack(
        track: BookTrackEntity,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val bookId = track.bookId
        val index = track.trackIndex
        // Back to QUEUED: from RUNNING (network error) or FAILED (server refused, affects the whole book).
        bookDao.requeueBookTrack(bookId, index)
        bookDao.queueBookDownload(bookId)
        if (bookDao.markBookTrackRunning(bookId, index) == 0) return DownloadOutcome.Cancelled
        val book = bookDao.book(bookId) ?: return DownloadOutcome.Cancelled
        val nodeId = channel.phoneNodeId() ?: return DownloadOutcome.Retry(IOException("Phone not connected"))
        val requestUid = WearBookFile.uid(book.serverId, book.remoteId, index)
        // A started server transfer does not match the file from the phone; the one from the phone has its own partial file.
        bookStorage.deletePart(bookId, index)
        val part = bookStorage.phonePartFileFor(bookId, index)
        val lengthFile = bookStorage.phoneLengthFileFor(bookId, index)
        bookStorage.ensureDirectory()
        return try {
            var lastReport = 0L
            val received = channel.receiveResumable(
                nodeId,
                requestUid,
                part,
                lengthFile,
                fits = { fitsForTransfer(it, bookStorage.allocatableBytes()) },
            ) { done, total ->
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    onProgress(done, total)
                }
            }
            when (received) {
                FileReceive.Missing -> {
                    bookDao.markBookDownloadFailed(bookId)
                    DownloadOutcome.Failed(MISSING_ON_PHONE)
                }
                is FileReceive.Received -> {
                    val target = bookStorage.freeFileFor(bookId, index, track.mimeType, bookDao.otherTrackFiles(bookId, index))
                    if (!part.renameTo(target)) throw IOException("Renaming to ${target.name} failed")
                    if (bookDao.markBookTrackDone(bookId, index, target.absolutePath, target.length()) == 0) {
                        target.delete()
                        DownloadOutcome.Cancelled
                    } else {
                        Log.d(TAG, "Book file loaded from the phone: $requestUid (${received.bytes} bytes)")
                        DownloadOutcome.Done
                    }
                }
            }
        } catch (e: CancellationException) {
            withContext(NonCancellable) { bookDao.requeueBookTrack(bookId, index) }
            throw e
        } catch (e: IOException) {
            if (e.isNoSpace() || e is FileDoesNotFit) {
                // With the length the phone named, the caller remembers the real size (review2 J7).
                Log.w(TAG, "Storage full while receiving $requestUid", e)
                withContext(NonCancellable + Dispatchers.IO) {
                    part.delete()
                    lengthFile.delete()
                }
                bookDao.unqueueTrack(bookId, index)
                return DownloadOutcome.NoSpace(totalBytes = (e as? FileDoesNotFit)?.totalBytes)
            }
            Log.w(TAG, "Transfer of $requestUid aborted (${part.length()} bytes kept for the next attempt)", e)
            DownloadOutcome.Retry(e)
        }
    }

    /**
     * Fetches the image of an episode or book from the phone (Wear concept 16.1/16.2): for files from the phone's device and
     * when the watch cannot load it itself. @return `null` if the phone has no image or is unreachable.
     */
    suspend fun fetchCover(bookId: Long): ByteArray? {
        val book = bookDao.book(bookId) ?: return null
        return fetchBytes(WearCoverFile.uid(book.serverId, book.remoteId))
    }

    /** Thumbnail of a phone album, subscription, book or episode for the lists in the "Phone" section (Wear concept 17). */
    suspend fun fetchThumbnail(ref: WearThumbnailFile.Ref): ByteArray? = fetchBytes(WearThumbnailFile.uid(ref))

    /** A small file from the phone into memory; `null` without phone, without file or if the transfer breaks off. */
    private suspend fun fetchBytes(requestUid: String): ByteArray? {
        val nodeId = channel.phoneNodeId() ?: return null
        return try {
            val output = ByteArrayOutputStream()
            when (withContext(Dispatchers.IO) { channel.receive(nodeId, requestUid, output) }) {
                FileReceive.Missing -> null
                is FileReceive.Received -> output.toByteArray()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Image $requestUid not fetched from the phone", e)
            null
        }
    }

    private suspend fun finish(uid: String, mimeType: String?, bytes: Long): DownloadOutcome {
        val part = storage.phonePartFileFor(uid)
        val target = storage.fileFor(uid, mimeType)
        if (!part.renameTo(target)) throw IOException("Renaming to ${target.name} failed")
        if (downloadDao.markDone(uid, target.absolutePath, target.length()) == 0) {
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Loaded from the phone: $uid ($bytes bytes)")
        return DownloadOutcome.Done
    }

    /** The entry was removed during the transfer (selection changed on the phone). */
    private class RemovedException : RuntimeException()

    companion object {
        private const val TAG = "PhoneFileFetcher"
        private const val PROGRESS_INTERVAL_NANOS = 500_000_000L

        /** Failure reason when the phone has no file (only logged). */
        const val MISSING_ON_PHONE = "Phone has no copy"
    }
}

/**
 * Whether [missingBytes] from the phone fit into the storage next to the reserve the server downloads keep free as well. Checked
 * before the first byte: the transfer used to run until the storage was full, without a size to remember, and the planner
 * queued the file again with its estimate, the next transfer starting at 0 (review2 J7).
 */
internal fun fitsForTransfer(missingBytes: Long, allocatableBytes: Long): Boolean =
    missingBytes <= allocatableBytes - TRANSFER_RESERVE_BYTES

/** Like the reserve of the server downloads (`ResumableTransfer`). */
internal const val TRANSFER_RESERVE_BYTES = 100L * 1024 * 1024
