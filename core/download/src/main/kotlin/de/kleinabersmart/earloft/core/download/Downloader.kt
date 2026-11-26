package de.kleinabersmart.earloft.core.download

import android.net.Network
import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.entity.DownloadEntity
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.model.DownloadFailure
import de.kleinabersmart.earloft.core.network.HttpStatusException
import de.kleinabersmart.earloft.core.network.isAuthRejection
import de.kleinabersmart.earloft.core.network.subsonic.subsonicErrorIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

/** Result of a single download. */
sealed interface DownloadOutcome {
    data object Done : DownloadOutcome

    /**
     * Failed permanently (e.g. file gone from the server). [sourceUnavailable]: this device doesn't know the source at all,
     * which affects all of its tracks. [failure]: the reason for the user (episodes store it).
     */
    data class Failed(
        val reason: String,
        val sourceUnavailable: Boolean = false,
        val failure: DownloadFailure = DownloadFailure.OTHER,
    ) : DownloadOutcome

    /**
     * The file doesn't fit into the storage, before or during the transfer (`ENOSPC`). The partial file is discarded, the
     * entry is still `RUNNING`: the caller decides when it is tried again (review H4, H7). [totalBytes]: the file's size
     * as the server reported it, if known.
     */
    data class NoSpace(val totalBytes: Long?) : DownloadOutcome

    /** Temporary error (network, rejected login); the entry is still `RUNNING`. */
    data class Retry(val cause: Exception) : DownloadOutcome {
        /** The server rejected the credentials: new ones will work (the watch then asks the phone). */
        val isAuthRejection: Boolean get() = cause.isAuthRejection

        /** The reason for the user if the file gives up after the retries. */
        val failure: DownloadFailure
            get() = when {
                isAuthRejection -> DownloadFailure.DENIED
                cause is HttpStatusException -> DownloadFailure.SERVER
                cause is DeviceStorageException -> DownloadFailure.OTHER
                else -> DownloadFailure.NETWORK
            }
    }

    /** The entry was removed during the download. */
    data object Cancelled : DownloadOutcome
}

/**
 * Downloads a song source completely into app storage (concept 5.3). The file is created as
 * `.part` and only renamed once fully received; aborted downloads therefore
 * never leave behind a half file that counts as finished.
 *
 * Auth is handled by the shared OkHttpClient based on the stream URL. `no-store` keeps the file
 * out of the HTTP cache.
 */
class Downloader @Inject constructor(
    private val downloadDao: DownloadDao,
    private val sources: MusicDataSources,
    private val client: OkHttpClient,
    private val storage: DownloadStorage,
) {
    /**
     * Downloads [download]. On [DownloadOutcome.Retry] the entry stays `RUNNING` – the
     * caller decides whether to re-enqueue it or mark it as failed.
     *
     * With [network] the connection runs over exactly this network (the watch downloads over Wi-Fi, never over the
     * phone's Bluetooth proxy).
     */
    suspend fun download(
        download: DownloadEntity,
        profile: DownloadProfile = DownloadProfile.ORIGINAL,
        network: Network? = null,
        onProgress: suspend (bytesDone: Long, bytesTotal: Long?) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val uid = download.sourceUid
        if (downloadDao.markRunning(uid) == 0) return DownloadOutcome.Cancelled
        val part = storage.partFileFor(uid)
        return try {
            fetch(uid, profile, network, onProgress).also { outcome ->
                if (outcome is DownloadOutcome.Failed) downloadDao.markFailed(uid)
                if (outcome !is DownloadOutcome.Done) part.delete()
            }
        } catch (e: CancellationException) {
            part.delete()
            withContext(NonCancellable) { downloadDao.requeue(uid) }
            throw e
        } catch (e: IOException) {
            part.delete()
            if (e.isNoSpace()) DownloadOutcome.NoSpace(totalBytes = null) else DownloadOutcome.Retry(e)
        }
    }

    /**
     * Whether the source of [uid] is known here (without network). If not, [download] ended with `Failed(sourceUnavailable)`:
     * the watch then asks the phone right away instead of first requesting Wi-Fi (review V4). Without an entry `true`.
     */
    suspend fun knowsSource(uid: String): Boolean {
        val songSource = downloadDao.getSongSource(uid) ?: return true
        return sources[songSource.sourceId] != null
    }

    private suspend fun fetch(
        uid: String,
        profile: DownloadProfile,
        network: Network?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): DownloadOutcome {
        val songSource = downloadDao.getSongSource(uid) ?: return DownloadOutcome.Cancelled
        val source = sources[songSource.sourceId]
            ?: return DownloadOutcome.Failed("Source unavailable", sourceUnavailable = true)
        val location = profile.transcodeTo
            ?.let { (codec, kbps) -> (source as? RemoteTranscoding)?.transcodedPlayback(songSource.remoteId, codec, kbps) }
            ?: source.resolvePlayback(songSource.remoteId) as? PlaybackLocation.Remote
            ?: return DownloadOutcome.Failed("No server source")

        val url = location.uri.toString().toHttpUrlOrNull()
            ?: return DownloadOutcome.Failed("Invalid address")
        val request = Request.Builder()
            .url(url)
            .cacheControl(CacheControl.Builder().noStore().build())
            .build()
        val call = clientFor(network, url.host).newCall(request)
        return call.cancelledWithCoroutine {
            withContext(Dispatchers.IO) {
                call.execute().use { response ->
                    val contentType = response.header("Content-Type").orEmpty()
                    when {
                        // Overloaded or rate-limited is temporary (review G25): 408 Request Timeout, 425 Too Early, 429 Too Many Requests.
                        response.code in 500..599 || response.code == 408 || response.code == 425 || response.code == 429 ->
                            throw IOException("HTTP ${response.code}")
                        // Rejected credentials are temporary: the password or token may have changed.
                        response.code == HTTP_UNAUTHORIZED -> throw HttpStatusException(response.code)
                        !response.isSuccessful -> return@withContext DownloadOutcome.Failed("HTTP ${response.code}")
                        // Subsonic reports errors (e.g. auth) as JSON/XML with HTTP 200, other servers via status.
                        contentType.startsWith("application/json") || contentType.contains("xml") -> {
                            val error = subsonicErrorIn(response.peekBody(MAX_ERROR_BODY_BYTES).string())
                            if (error?.isRejectedCredentials == true) throw error
                            return@withContext DownloadOutcome.Failed(error?.message ?: "Server error instead of audio file")
                        }
                        // The login page of a proxy, a captive portal or a maintenance page is no audio, however complete it arrives;
                        // book and episode downloads reject it as well (review4 T4).
                        contentType.trim().lowercase().startsWith("text/") ->
                            return@withContext DownloadOutcome.Failed("Not an audio file (${contentType.substringBefore(';').trim()})")
                    }
                    // What Android lets the download use and what the parallel downloads still need (review H7).
                    val total = response.body.contentLength().takeIf { it >= 0 }
                    val key = "song:$uid"
                    if (total != null && !RunningDownloads.claim(key, total, storage.allocatableBytes(), FREE_SPACE_RESERVE_BYTES)) {
                        return@withContext DownloadOutcome.NoSpace(total)
                    }
                    val claimingProgress: suspend (Long, Long?) -> Unit = { done, size ->
                        if (size != null) RunningDownloads.update(key, size - done)
                        onProgress(done, size)
                    }
                    try {
                        writeToPart(uid, response.body.byteStream(), total, claimingProgress)
                    } finally {
                        RunningDownloads.release(key)
                    } ?: return@withContext DownloadOutcome.Cancelled
                    // The extension follows the response: a transcoded file has a different type than the source.
                    val responseType = contentType.substringBefore(';').trim().lowercase()
                    val mimeType = responseType.takeIf { it.startsWith("audio/") }
                        ?: songSource.mimeType
                        ?: responseType.ifEmpty { null }
                    finish(uid, mimeType, total)
                }
            }
        }
    }

    /** Loopback (e.g. `adb reverse` in the emulator) never leaves the device and can't be bound to a network. */
    private fun clientFor(network: Network?, host: String): OkHttpClient =
        if (network == null || host == "localhost" || host == "127.0.0.1" || host == "[::1]") {
            client
        } else {
            client.newBuilder()
                .socketFactory(network.socketFactory)
                .dns { host -> network.getAllByName(host).toList() }
                .build()
        }

    /** @return bytes written, or `null` if the download was removed in the meantime. */
    private suspend fun writeToPart(
        uid: String,
        input: java.io.InputStream,
        total: Long?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): Long? {
        val part = storage.partFileFor(uid)
        onDevice { storage.ensureDirectory() }
        var written = 0L
        var lastReport = 0L
        onDevice { part.outputStream() }.use { output ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                // After the read, which may have waited long: a cancelled download writes nothing more (review H31).
                currentCoroutineContext().ensureActive()
                onDevice { output.write(buffer, 0, read) }
                written += read
                val now = System.nanoTime()
                if (now - lastReport >= PROGRESS_INTERVAL_NANOS) {
                    lastReport = now
                    if (downloadDao.updateProgress(uid, written, total) == 0) return null
                    onProgress(written, total)
                }
            }
        }
        if (total != null && written != total) throw IOException("Incomplete: $written of $total bytes")
        return written
    }

    private suspend fun finish(uid: String, mimeType: String?, total: Long?): DownloadOutcome {
        val part = storage.partFileFor(uid)
        val target = storage.fileFor(uid, mimeType)
        if (!part.renameTo(target)) throw DeviceStorageException("Renaming to ${target.name} failed")
        val size = target.length()
        if (downloadDao.markDone(uid, target.absolutePath, size) == 0) {
            target.delete()
            return DownloadOutcome.Cancelled
        }
        Log.d(TAG, "Download done: $uid ($size bytes${if (total == null) ", size unknown beforehand" else ""})")
        return DownloadOutcome.Done
    }

    private companion object {
        const val TAG = "Downloader"
        const val BUFFER_BYTES = 64 * 1024
        const val PROGRESS_INTERVAL_NANOS = 500_000_000L
        const val FREE_SPACE_RESERVE_BYTES = 100L * 1024 * 1024
        const val HTTP_UNAUTHORIZED = 401

        /** Error responses are small; never read more than this. */
        const val MAX_ERROR_BODY_BYTES = 16L * 1024
    }
}
