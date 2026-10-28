package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val COVER_SIZE_PX = 128

/** Grundfarbe eines Albums für das Farbschema; wird einmal aus dem Cover berechnet und in `albums.seedColor` gespeichert. */
@Singleton
class AlbumColorRepository @Inject constructor(
    private val libraryDao: LibraryDao,
    private val artwork: ArtworkRepository,
) {
    /** Die gespeicherte Farbe (ARGB), `null` solange sie fehlt; fehlt sie, wird sie berechnet und der Fluss liefert sie danach. */
    fun seedColor(albumId: Long): Flow<Int?> =
        libraryDao.observeSeedColor(albumId).onEach { if (it == null) compute(albumId) }

    /** Einmalige Abfrage (z. B. für das Widget): gespeicherte Farbe oder – falls nötig – frisch berechnet. */
    suspend fun seedColorNow(albumId: Long): Int? =
        libraryDao.observeSeedColor(albumId).first() ?: compute(albumId)

    private suspend fun compute(albumId: Long): Int? {
        val bitmap = artwork.albumCover(albumId, COVER_SIZE_PX) ?: return null
        val color = withContext(Dispatchers.Default) { seedColorOf(bitmap) }
        libraryDao.setSeedColor(albumId, color)
        return color
    }
}
