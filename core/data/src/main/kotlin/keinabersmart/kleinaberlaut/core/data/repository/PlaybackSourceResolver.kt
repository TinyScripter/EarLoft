package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.model.PlayableSourceRow
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wählt beim Abspielen die beste Quelle eines Songs (Konzept 6.5): lokal vor Stream, dann nach
 * Quellen-Priorität, verlustfrei vor verlustbehaftet, dann Bitrate. Downloads folgen in Phase 2b.
 */
@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: MusicDataSources,
) {
    suspend fun resolve(songId: Long): PlaybackLocation? {
        val best = libraryDao.getPlayableSources(songId).sortedWith(PREFERENCE).firstOrNull() ?: return null
        return registry[best.sourceId]?.resolvePlayback(best.remoteId)
    }

    internal companion object {
        private val LOSSLESS_MIME_TYPES = setOf(
            "audio/flac", "audio/x-flac", "audio/alac", "audio/x-alac", "audio/wav", "audio/x-wav",
            "audio/aiff", "audio/x-aiff", "audio/x-ape", "audio/x-wavpack",
        )

        val PREFERENCE: Comparator<PlayableSourceRow> =
            compareBy<PlayableSourceRow> { if (it.sourceType == SourceType.LOCAL) 0 else 1 }
                .thenByDescending { it.sourcePriority }
                .thenByDescending { it.mimeType?.lowercase() in LOSSLESS_MIME_TYPES }
                .thenByDescending { it.bitRate ?: 0 }
    }
}
