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
 * Wählt beim Abspielen die beste Quelle eines Songs (Konzept 6.5): lokale Datei, dann fertiger
 * Download, dann Stream – Streams nach [SourcePreference]. Über mobile Daten mit eingestellter Qualität kommen zuerst die
 * vom Server umgewandelten Streams (Konzept 7.4).
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
     * Alle abspielbaren Orte eines Songs, der beste zuerst. Ist einer nicht erreichbar, nimmt die
     * Wiedergabe den nächsten (Konzept 6.5). Umgewandelte Streams stehen vor den Originalen, die als Rückfall bleiben.
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
        if (policy.allowStreaming) {
            val transcodeTo = if (remote.isEmpty()) null else mobileStreaming.transcodeTo()
            if (transcodeTo != null) remote.mapNotNullTo(result) { transcoded(it, transcodeTo) }
            remote.mapNotNullTo(result) { resolveSource(it) }
        }
        return result
    }

    /**
     * Nur, wenn der Server umwandeln kann und es spürbar spart: Die Quelle hat mehr als das [MIN_SAVING]-fache der Ziel-Bitrate
     * (oder keine bekannte). Opus 135 kbit/s noch einmal in Opus 128 umzuwandeln, kostet nur Klang.
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
        const val MIN_SAVING = 1.25
    }
}

/**
 * Wo ein Song gespielt wird; [replayGain] ist `null`, wenn die Quelle keine Werte kennt.
 * [sourceId] ist `null` für Downloads (liegen auf dem Gerät, unabhängig von der Erreichbarkeit).
 * [transcodedTo] sind Format und Bitrate (kbit/s), in die der Server umwandelt; `null` für das Original.
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

/** Qualität einer Datei zum Vergleichen: verlustfrei vor verlustbehaftet, dann die höhere Bitrate (unbekannt = 0). */
internal fun audioQuality(mimeType: String?, bitRate: Int?): Long =
    (if (isLossless(mimeType)) 1L shl 32 else 0L) + (bitRate ?: 0).coerceAtLeast(0)

/** Reihenfolge der Quellen eines Songs: lokal, Server-Priorität, verlustfrei, Bitrate. */
object SourcePreference : Comparator<PlayableSourceRow> {
    private val comparator =
        compareBy<PlayableSourceRow> { if (it.sourceType == SourceType.LOCAL) 0 else 1 }
            .thenByDescending { it.sourcePriority }
            .thenByDescending { isLossless(it.mimeType) }
            .thenByDescending { it.bitRate ?: 0 }

    override fun compare(a: PlayableSourceRow, b: PlayableSourceRow): Int = comparator.compare(a, b)
}
