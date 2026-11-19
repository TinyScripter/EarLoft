package de.kleinabersmart.earloft.core.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import de.kleinabersmart.earloft.core.data.repository.PlaybackSourceResolver
import de.kleinabersmart.earloft.core.data.repository.ResolvedPlayback
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.model.AppUris
import de.kleinabersmart.earloft.core.model.ReplayGain
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Opens `earloft://song/<id>` at the best reachable source (concept 6.5/7.2). If opening fails at the
 * start of the file, the next source is tried. In the middle of the file (seek, retry
 * after an abort) it stays with the source that delivered the start – byte positions
 * of different files don't match.
 *
 * Runs on ExoPlayer's loader thread, so blocking is allowed here.
 */
@OptIn(UnstableApi::class)
internal class SongDataSource(
    private val upstream: DataSource,
    /** See [PlaybackSourceResolver.candidates]. */
    private val candidatesOf: suspend (songId: Long) -> List<ResolvedPlayback>,
    private val health: SourceHealth,
    /** Text of the error message if no source is reachable; Android Auto shows it to the driver. */
    private val unavailableMessage: String = "No playable source",
    /** ReplayGain of the chosen source; reported before the samples are played. */
    private val onResolved: (songId: Long, replayGain: ReplayGain?) -> Unit,
    /** The location that delivered the start (title info). */
    private val onOpened: (songId: Long, playback: ResolvedPlayback) -> Unit = { _, _ -> },
    /** Whether a stream with this cache key is completely in the stream cache, see [preferCachedOriginals]. */
    private val isFullyCached: (cacheKey: String) -> Boolean = { false },
) : DataSource {

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        val songId = AppUris.parseSongId(dataSpec.uri.toString()) ?: return upstream.open(dataSpec)
        val candidates = health.reachableFirst(runBlocking { candidatesOf(songId) }.preferCachedOriginals(isFullyCached))
        if (candidates.isEmpty()) throw IOException(unavailableMessage)

        val tries = if (dataSpec.position == 0L) {
            candidates
        } else {
            // Even if the location is no longer among the candidates (e.g. the transcode after switching to Wi-Fi):
            // byte positions of another version don't match.
            val openedFrom = health.openedFrom(songId)
            listOf(candidates.firstOrNull { it.key == openedFrom?.key } ?: openedFrom ?: candidates.first())
        }
        var lastError: IOException? = null
        for (candidate in tries) {
            try {
                val length = upstream.open(candidate.applyTo(dataSpec))
                health.succeeded(candidate.sourceId)
                health.rememberOpened(songId, candidate)
                onResolved(songId, candidate.replayGain)
                if (dataSpec.position == 0L) onOpened(songId, candidate)
                return length
            } catch (e: IOException) {
                Log.w(TAG, "Song $songId: ${candidate.key} nicht erreichbar", e)
                runCatching { upstream.close() }
                health.failed(candidate.sourceId)
                lastError = e
            }
        }
        throw lastError ?: IOException(unavailableMessage)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = upstream.read(buffer, offset, length)

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()

    class Factory(
        private val upstream: DataSource.Factory,
        private val sourceResolver: PlaybackSourceResolver,
        private val health: SourceHealth,
        private val unavailableMessage: String,
        private val onResolved: (songId: Long, replayGain: ReplayGain?) -> Unit,
        private val onOpened: (songId: Long, playback: ResolvedPlayback) -> Unit = { _, _ -> },
        private val isFullyCached: (cacheKey: String) -> Boolean = { false },
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SongDataSource(upstream.createDataSource(), sourceResolver::candidates, health, unavailableMessage, onResolved, onOpened, isFullyCached)
    }

    private companion object {
        const val TAG = "SongDataSource"
    }
}

/**
 * Over mobile data (transcoded candidates present): an original that is already completely in the stream cache costs nothing and
 * sounds better – it moves ahead of the transcodes. Local files and downloads before it stay where they are.
 */
internal fun List<ResolvedPlayback>.preferCachedOriginals(isFullyCached: (cacheKey: String) -> Boolean): List<ResolvedPlayback> {
    val firstTranscoded = indexOfFirst { it.transcodedTo != null }
    if (firstTranscoded < 0) return this
    val rest = drop(firstTranscoded)
    val cached = rest.filter { it.transcodedTo == null && (it.location as? PlaybackLocation.Remote)?.cacheKey?.let(isFullyCached) == true }
    if (cached.isEmpty()) return this
    return take(firstTranscoded) + cached + rest.filterNot { it in cached }
}

/** Identifier of a location – for streams the cache key (`sourceUid`), otherwise the URI. */
internal val ResolvedPlayback.key: String
    get() = when (val location = location) {
        is PlaybackLocation.LocalFile -> location.uri.toString()
        is PlaybackLocation.Remote -> location.cacheKey
    }

@OptIn(UnstableApi::class)
private fun ResolvedPlayback.applyTo(dataSpec: DataSpec): DataSpec = when (val location = location) {
    is PlaybackLocation.LocalFile -> dataSpec.withUri(location.uri)
    is PlaybackLocation.Remote -> dataSpec.buildUpon().setUri(location.uri).setKey(location.cacheKey).build()
}

/**
 * Remembers which sources were just unreachable, so that not every following title first
 * runs into the same timeout, and from which location a song was last opened.
 */
internal class SourceHealth(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
) {
    private val failedAt = ConcurrentHashMap<String, Long>()
    private val openedFrom = ConcurrentHashMap<Long, ResolvedPlayback>()

    fun failed(sourceId: String?) {
        if (sourceId != null) failedAt[sourceId] = clock()
    }

    fun succeeded(sourceId: String?) {
        if (sourceId != null) failedAt.remove(sourceId)
    }

    /** Stable: the order stays, only recently failed sources move to the back. */
    fun reachableFirst(candidates: List<ResolvedPlayback>): List<ResolvedPlayback> {
        val now = clock()
        return candidates.sortedBy { candidate ->
            candidate.sourceId?.let { failedAt[it] }?.let { now - it < RETRY_AFTER_MS } == true
        }
    }

    fun rememberOpened(songId: Long, playback: ResolvedPlayback) {
        openedFrom[songId] = playback
    }

    fun openedFrom(songId: Long): ResolvedPlayback? = openedFrom[songId]

    private companion object {
        const val RETRY_AFTER_MS = 2 * 60 * 1000L
    }
}
