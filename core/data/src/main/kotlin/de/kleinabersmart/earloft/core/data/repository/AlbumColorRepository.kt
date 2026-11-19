package de.kleinabersmart.earloft.core.data.repository

import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val COVER_SIZE_PX = 128

/** Seed color of an album for the color scheme; computed once from the cover and stored in `albums.seedColor`. */
@Singleton
class AlbumColorRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val artwork: ArtworkRepository,
) {
    /** The stored color (ARGB), `null` while missing; if missing, it is computed and the flow emits it afterwards. */
    fun seedColor(albumId: Long): Flow<Int?> =
        libraryDao.observeSeedColor(albumId).onEach { if (it == null) compute(albumId) }

    /** One-off query (e.g. for the widget): stored color or – if necessary – freshly computed. */
    suspend fun seedColorNow(albumId: Long): Int? =
        libraryDao.observeSeedColor(albumId).first() ?: compute(albumId)

    private suspend fun compute(albumId: Long): Int? {
        val bitmap = artwork.albumCover(albumId, COVER_SIZE_PX) ?: return null
        val color = withContext(Dispatchers.Default) { seedColorOf(bitmap) }
        libraryDao.setSeedColor(albumId, color)
        return color
    }
}
