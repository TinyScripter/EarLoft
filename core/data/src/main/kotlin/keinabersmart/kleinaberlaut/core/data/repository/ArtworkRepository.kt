package keinabersmart.kleinaberlaut.core.data.repository

import android.graphics.Bitmap
import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.data.source.MusicDataSources
import javax.inject.Inject
import javax.inject.Singleton

/** Lädt Cover unabhängig davon, aus welcher Quelle sie stammen. */
@Singleton
class ArtworkRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val registry: MusicDataSources,
    private val coverFiles: CoverFiles,
) {
    /** Zuerst die Cover-Datei auf dem Gerät (Uhr, Wear-Konzept 7.3), sonst die Quelle. */
    suspend fun albumCover(albumId: Long, sizePx: Int): Bitmap? {
        coverFiles.read(albumId)?.let { return it }
        return loadFromSource(albumId, sizePx)
    }

    /** Holt das Cover von der Quelle und legt es als Datei ab, falls noch keine da ist. @return `true`, wenn jetzt eine Datei da ist. */
    suspend fun cacheAlbumCover(albumId: Long, sizePx: Int): Boolean {
        if (coverFiles.has(albumId)) return true
        val bitmap = loadFromSource(albumId, sizePx) ?: return false
        coverFiles.write(albumId, bitmap)
        return true
    }

    private suspend fun loadFromSource(albumId: Long, sizePx: Int): Bitmap? {
        val source = libraryDao.getCoverSource(albumId) ?: return null
        return registry[source.sourceId]?.loadArtwork(source.coverRemoteId ?: source.remoteId, sizePx)
    }
}
