package keinabersmart.kleinaberlaut.core.data.repository

import android.net.Uri
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.database.dao.DownloadDao
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.model.PlayableSourceRow
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.SourceType
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
) {
    suspend fun resolve(songId: Long): PlaybackLocation? {
        val sources = libraryDao.getPlayableSources(songId).sortedWith(SourcePreference)
        val best = sources.firstOrNull() ?: return null
        if (best.sourceType != SourceType.LOCAL) {
            downloadedFile(songId)?.let { return PlaybackLocation.LocalFile(Uri.fromFile(it)) }
        }
        return registry[best.sourceId]?.resolvePlayback(best.remoteId)
    }

    /** Fehlt die Datei (z. B. vom System gelöscht), wird gestreamt. */
    private suspend fun downloadedFile(songId: Long): File? =
        downloadDao.getDoneFilePaths(songId).map(::File).firstOrNull { it.isFile }
}

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
