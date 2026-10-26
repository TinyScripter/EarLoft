package keinabersmart.kleinaberlaut.core.data.repository

import android.graphics.Bitmap
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.datasource.DataSourceRegistry
import javax.inject.Inject
import javax.inject.Singleton

/** Lädt Cover unabhängig davon, aus welcher Quelle sie stammen. */
@Singleton
class ArtworkRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: DataSourceRegistry,
) {
    suspend fun albumCover(albumId: Long, sizePx: Int): Bitmap? {
        val source = libraryDao.getCoverSource(albumId) ?: return null
        return registry[source.sourceId]?.loadArtwork(source.remoteId, sizePx)
    }
}
