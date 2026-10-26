package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.database.model.PlayableSourceRow
import keinabersmart.kleinaberlaut.core.datasource.DataSourceRegistry
import keinabersmart.kleinaberlaut.core.datasource.PlaybackLocation
import keinabersmart.kleinaberlaut.core.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wählt beim Abspielen die beste Quelle eines Songs (Konzept 6.5):
 * lokal vor Stream, dann nach Quellen-Priorität und Bitrate. Downloads folgen in Phase 2.
 */
@Singleton
class PlaybackSourceResolver @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: DataSourceRegistry,
) {
    suspend fun resolve(songId: Long): PlaybackLocation? {
        val best = libraryDao.getPlayableSources(songId).sortedWith(PREFERENCE).firstOrNull() ?: return null
        return registry[best.sourceId]?.resolvePlayback(best.remoteId)
    }

    internal companion object {
        val PREFERENCE: Comparator<PlayableSourceRow> =
            compareBy<PlayableSourceRow> { if (it.sourceType == SourceType.LOCAL) 0 else 1 }
                .thenByDescending { it.sourcePriority }
                .thenByDescending { it.bitRate ?: 0 }
    }
}
