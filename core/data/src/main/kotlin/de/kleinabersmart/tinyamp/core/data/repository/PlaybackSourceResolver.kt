package de.kleinabersmart.tinyamp.core.data.repository

import android.net.Uri
import android.util.Log
import de.kleinabersmart.tinyamp.core.data.source.MusicDataSources
import de.kleinabersmart.tinyamp.core.database.dao.DownloadDao
import de.kleinabersmart.tinyamp.core.database.dao.LibraryDao
import de.kleinabersmart.tinyamp.core.database.model.PlayableSourceRow
import de.kleinabersmart.tinyamp.core.datasource.PlaybackLocation
import de.kleinabersmart.tinyamp.core.model.ReplayGain
import de.kleinabersmart.tinyamp.core.model.SourceType
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wählt beim Abspielen die beste Quelle eines Songs (Konzept 6.5): lokale Datei, dann fertiger
 * Download, dann Stream – Streams nach [SourcePreference].
 */
@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val downloadDao: DownloadDao,
    private val registry: MusicDataSources,
    private val policy: PlaybackPolicy,
) {
    /**
     * Alle abspielbaren Orte eines Songs, der beste zuerst. Ist einer nicht erreichbar, nimmt die
     * Wiedergabe den nächsten (Konzept 6.5).
     */
    suspend fun candidates(songId: Long): List<ResolvedPlayback> {
        val (local, remote) = libraryDao.getPlayableSources(songId)
            .sortedWith(SourcePreference)
            .partition { it.sourceType == SourceType.LOCAL }
        val result = mutableListOf<ResolvedPlayback>()
        local.mapNotNullTo(result) { resolveSource(it) }
        if (remote.isNotEmpty()) {
            // Ein Download ist eine Kopie der Server-Datei, die Werte der Quelle gelten also auch für ihn.
            downloadedFile(songId)?.let {
                result += ResolvedPlayback(PlaybackLocation.LocalFile(Uri.fromFile(it)), replayGain(remote.first().uid), sourceId = null)
            }
        }
        if (policy.allowStreaming) remote.mapNotNullTo(result) { resolveSource(it) }
        return result
    }

    private suspend fun resolveSource(row: PlayableSourceRow): ResolvedPlayback? {
        val location = try {
            registry[row.sourceId]?.resolvePlayback(row.remoteId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Quelle ${row.uid} nicht nutzbar", e)
            null
        } ?: return null
        return ResolvedPlayback(location, replayGain(row.uid), row.sourceId)
    }

    private suspend fun replayGain(uid: String): ReplayGain? =
        libraryDao.getReplayGain(uid)
            ?.let { ReplayGain(it.rgTrackGainDb, it.rgTrackPeak, it.rgAlbumGainDb, it.rgAlbumPeak) }
            ?.takeUnless { it.isEmpty }

    /** Fehlt die Datei (z. B. vom System gelöscht), wird gestreamt. */
    private suspend fun downloadedFile(songId: Long): File? =
        downloadDao.getDoneFilePaths(songId).map(::File).firstOrNull { it.isFile }

    private companion object {
        const val TAG = "PlaybackSourceResolver"
    }
}

/**
 * Wo ein Song gespielt wird; [replayGain] ist `null`, wenn die Quelle keine Werte kennt.
 * [sourceId] ist `null` für Downloads (liegen auf dem Gerät, unabhängig von der Erreichbarkeit).
 */
data class ResolvedPlayback(val location: PlaybackLocation, val replayGain: ReplayGain?, val sourceId: String?)

/** Reihenfolge der Quellen eines Songs: lokal, Server-Priorität, verlustfrei, Bitrate. */
object SourcePreference : Comparator<PlayableSourceRow> {
    private val LOSSLESS_MIME_TYPES = setOf(
        "audio/flac", "audio/x-flac", "audio/alac", "audio/x-alac", "audio/wav", "audio/x-wav",
        "audio/aiff", "audio/x-aiff", "audio/x-ape", "audio/x-wavpack",
    )

    private val comparator =
        compareBy<PlayableSourceRow> { if (it.sourceType == SourceType.LOCAL) 0 else 1 }
            .thenByDescending { it.sourcePriority }
            .thenByDescending { it.mimeType?.lowercase() in LOSSLESS_MIME_TYPES }
            .thenByDescending { it.bitRate ?: 0 }

    override fun compare(a: PlayableSourceRow, b: PlayableSourceRow): Int = comparator.compare(a, b)
}
