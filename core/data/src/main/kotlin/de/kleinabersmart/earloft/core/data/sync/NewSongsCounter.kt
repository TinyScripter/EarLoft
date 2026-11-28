package de.kleinabersmart.earloft.core.data.sync

import de.kleinabersmart.earloft.core.data.refresh.NewAlbum
import de.kleinabersmart.earloft.core.database.dao.LibraryDao
import de.kleinabersmart.earloft.core.database.dao.SourceDao
import javax.inject.Inject

/**
 * Counts the songs a sync newly created: [begin] before the sync, [newSongs] afterwards. Songs get a
 * sequential `songId` that's never reused; so new is whatever has a larger id than the highest song before.
 * Retagged files keep their id and don't count as new, nor do songs that took over an older one after the server changed the
 * track's ID ([AdoptedSongs], review5 P23).
 */
class NewSongsCounter @Inject constructor(
    private val libraryDao: LibraryDao,
    private val sourceDao: SourceDao,
    private val adopted: AdoptedSongs = AdoptedSongs(),
) {
    /** [firstImport]: one of the sources is read for the first time; then everything is "new" and isn't counted. */
    data class Baseline(val maxSongId: Long, val firstImport: Boolean)

    suspend fun begin(sourceIds: List<String>): Baseline = Baseline(
        maxSongId = libraryDao.maxSongId(),
        firstImport = sourceIds.any { (sourceDao.get(it)?.lastFullSyncAt ?: 0L) == 0L },
    )

    suspend fun newSongs(baseline: Baseline): Int =
        if (baseline.firstImport) 0 else newSongsByAlbum(baseline).values.sumOf { it.size }

    /** The albums of the new songs ([NewAlbum.songs] counts only the new ones), those with the most new ones first. */
    suspend fun newAlbums(baseline: Baseline): List<NewAlbum> {
        if (baseline.firstImport) return emptyList()
        val byAlbum = newSongsByAlbum(baseline)
        if (byAlbum.isEmpty()) return emptyList()
        return libraryDao.albumsWithSongsAfter(baseline.maxSongId)
            .mapNotNull { album -> byAlbum[album.albumId]?.let { NewAlbum(album.albumId, album.title, album.albumArtistDisplay, it.size) } }
            .sortedByDescending { it.songs }
    }

    private suspend fun newSongsByAlbum(baseline: Baseline): Map<Long, List<Long>> =
        libraryDao.visibleSongsAfter(baseline.maxSongId)
            .filterNot { it.songId in adopted }
            .groupBy({ it.albumId }, { it.songId })
}
