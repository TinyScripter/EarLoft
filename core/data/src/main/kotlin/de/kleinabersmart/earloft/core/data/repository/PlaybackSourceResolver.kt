package de.kleinabersmart.earloft.core.data.repository

import android.net.Uri
import android.util.Log
import de.kleinabersmart.earloft.core.data.source.MusicDataSources
import de.kleinabersmart.earloft.core.database.dao.DownloadDao
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.datasource.PlaybackLocation
import de.kleinabersmart.earloft.core.datasource.RemoteTranscoding
import de.kleinabersmart.earloft.core.model.ReplayGain
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Picks the best source of a song for playback (concept 6.5): local file, then finished
 * download, then stream – streams by [SourcePreference]. Over mobile data with a configured quality, the
 * streams transcoded by the server come first (concept 7.4).
 */
@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
    private val registry: MusicDataSources,
    private val policy: PlaybackPolicy,
    private val mobileStreaming: MobileStreaming,
) {
    /**
     * All playable locations of a song, the best first. If one is unreachable,
     * playback takes the next one (concept 6.5). Transcoded streams come before the originals, which remain as a fallback.
     */
    suspend fun candidates(songId: Long): List<ResolvedPlayback> {
        val (local, remote) = libraryDao.getPlayableSources(songId)
            .sortedWith(SourcePreference)
            .partition { it.sourceType == SourceType.LOCAL }
        val result = mutableListOf<ResolvedPlayback>()
        local.mapNotNullTo(result) { resolveSource(it) }
        if (remote.isNotEmpty()) {
            // A download is a copy of the server file, so the source's values apply to it as well.
            downloadedFile(songId)?.let {
                result += ResolvedPlayback(PlaybackLocation.LocalFile(Uri.fromFile(it)), replayGain(remote.first().uid), sourceId = null)
            }
        }
        if (policy.allowStreaming) {
            val transcodeTo = if (remote.isEmpty()) null else mobileStreaming.transcodeTo()
            if (transcodeTo != null) remote.mapNotNullTo(result) { transcoded(it, transcodeTo) }
            remote.mapNotNullTo(result) { resolveSource(it) }
        }
        return result
    }

    /**
     * Only if the server can transcode and it noticeably saves: the source has more than [MIN_SAVING] times the target bitrate
     * (or none known). Transcoding Opus 135 kbit/s again into Opus 128 only costs sound quality.
     */
    private suspend fun transcoded(row: PlayableSourceRow, transcodeTo: Pair<String, Int>): ResolvedPlayback? {
        val (codec, kbps) = transcodeTo
        if (row.bitRate?.let { it > 0 && it <= kbps * 1000 * MIN_SAVING } == true) return null
        val source = registry[row.sourceId] as? RemoteTranscoding ?: return null
        return ResolvedPlayback(source.transcodedPlayback(row.remoteId, codec, kbps), replayGain(row.uid), row.sourceId, transcodeTo)
    }

    private suspend fun resolveSource(row: PlayableSourceRow): ResolvedPlayback? {
        val location = try {
            registry[row.sourceId]?.resolvePlayback(row.remoteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Source ${row.uid} not usable", e)
            null
        } ?: return null
        return ResolvedPlayback(location, replayGain(row.uid), row.sourceId)
    }

    private suspend fun replayGain(uid: String): ReplayGain? =
        libraryDao.getReplayGain(uid)
            ?.let { ReplayGain(it.rgTrackGainDb, it.rgTrackPeak, it.rgAlbumGainDb, it.rgAlbumPeak) }
            ?.takeUnless { it.isEmpty }

    /** If the file is missing (e.g. deleted by the system), it is streamed. */
    private suspend fun downloadedFile(songId: Long): File? =
        downloadDao.getDoneFilePaths(songId).map(::File).firstOrNull { it.isFile }

    private companion object {
        const val TAG = "PlaybackSourceResolver"
        const val MIN_SAVING = 1.25
    }
}

/**
 * Where a song is played; [replayGain] is `null` if the source has no values.
 * [sourceId] is `null` for downloads (they are on the device, independent of reachability).
 * [transcodedTo] are the format and bitrate (kbit/s) the server transcodes to; `null` for the original.
 */
data class ResolvedPlayback(
    val location: PlaybackLocation,
    val replayGain: ReplayGain?,
    val sourceId: String?,
    val transcodedTo: Pair<String, Int>? = null,
)

private val LOSSLESS_MIME_TYPES = setOf(
    "audio/flac", "audio/x-flac", "audio/alac", "audio/x-alac", "audio/wav", "audio/x-wav",
    "audio/aiff", "audio/x-aiff", "audio/x-ape", "audio/x-wavpack",
)

internal fun isLossless(mimeType: String?): Boolean = mimeType?.lowercase() in LOSSLESS_MIME_TYPES

/** Quality of a file for comparison: lossless before lossy, then the higher bitrate (unknown = 0). */
internal fun audioQuality(mimeType: String?, bitRate: Int?): Long =
    (if (isLossless(mimeType)) 1L shl 32 else 0L) + (bitRate ?: 0).coerceAtLeast(0)

/** Order of a song's sources: local, server priority, lossless, bitrate. */
object SourcePreference : Comparator<PlayableSourceRow> {
    private val comparator =
        compareBy<PlayableSourceRow> { if (it.sourceType == SourceType.LOCAL) 0 else 1 }
            .thenByDescending { it.sourcePriority }
            .thenByDescending { isLossless(it.mimeType) }
            .thenByDescending { it.bitRate ?: 0 }

    override fun compare(a: PlayableSourceRow, b: PlayableSourceRow): Int = comparator.compare(a, b)
}
