package de.kleinabersmart.kleinaberlaut.core.data.repository

import de.kleinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import de.kleinabersmart.kleinaberlaut.core.model.Album
import de.kleinabersmart.kleinaberlaut.core.model.AlbumDetails
import de.kleinabersmart.kleinaberlaut.core.model.Artist
import de.kleinabersmart.kleinaberlaut.core.model.ArtistDetails
import de.kleinabersmart.kleinaberlaut.core.model.SearchResults
import de.kleinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryRepository @Inject constructor(
    private val libraryDao: LibraryDao,
) {
    fun albums(): Flow<List<Album>> = libraryDao.observeAlbumRows().map { rows -> rows.map { it.toAlbum() } }

    fun artists(): Flow<List<Artist>> = libraryDao.observeArtistRows().map { rows -> rows.map { it.toArtist() } }

    fun songs(): Flow<List<Song>> = libraryDao.observeSongRows().map { rows -> rows.map { it.toSong() } }

    fun album(albumId: Long): Flow<AlbumDetails?> =
        combine(libraryDao.observeAlbumRow(albumId), libraryDao.observeSongRowsOfAlbum(albumId)) { album, songs ->
            album?.let { AlbumDetails(it.toAlbum(), songs.map { row -> row.toSong() }) }
        }

    fun artist(artistId: Long): Flow<ArtistDetails?> =
        combine(libraryDao.observeArtistRow(artistId), libraryDao.observeAlbumRowsOfArtist(artistId)) { artist, albums ->
            artist?.let { ArtistDetails(it.toArtist(), albums.map { row -> row.toAlbum() }) }
        }

    /** Die Alben jedes Künstlers (Albumkünstler): Künstler-ID → Album-IDs. */
    fun albumIdsByArtist(): Flow<Map<Long, Set<Long>>> = libraryDao.observeAlbumArtists().map { refs ->
        refs.groupBy({ it.artistId }, { it.albumId }).mapValues { it.value.toSet() }
    }

    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyAddedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    fun recentlyPlayedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyPlayedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    /** Gespielte Alben, die seit mindestens [notPlayedForMs] nicht mehr liefen; das älteste zuerst. */
    fun rediscoverAlbums(notPlayedForMs: Long = REDISCOVER_AFTER_MS, limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRediscoverAlbumRows(System.currentTimeMillis() - notPlayedForMs, limit)
            .map { rows -> rows.map { it.toAlbum() } }

    /**
     * Zufällige Alben. Die Auswahl bleibt stabil und wird nur neu gewürfelt, wenn sich die Zahl
     * der Alben ändert (z. B. nach dem ersten Scan) – nicht bei jeder Datenbankänderung.
     */
    fun randomAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomAlbumRows(limit).map { row -> row.toAlbum() } }

    /** Zufällige Künstler, Verhalten wie bei [randomAlbums]. */
    fun randomArtists(limit: Int = 20): Flow<List<Artist>> =
        libraryDao.observeAlbumCount().distinctUntilChanged()
            .map { libraryDao.getRandomArtistRows(limit).map { row -> row.toArtist() } }

    /** Liefert die Songs in der Reihenfolge von [songIds]; unbekannte IDs fallen weg. */
    suspend fun songs(songIds: List<Long>): List<Song> {
        val byId = songIds.distinct()
            .chunked(SQLITE_MAX_VARIABLES)
            .flatMap { libraryDao.getSongRows(it) }
            .associate { it.songId to it.toSong() }
        return songIds.mapNotNull { byId[it] }
    }

    /** Welche der Song-Quellen (`sourceUid`) es noch gibt. */
    suspend fun existingSourceUids(uids: Collection<String>): Set<String> =
        uids.chunked(SQLITE_MAX_VARIABLES).flatMapTo(HashSet()) { libraryDao.getExistingSourceUids(it) }

    suspend fun search(query: String, limit: Int = 50): SearchResults {
        val match = FtsQuery.build(query) ?: return SearchResults.EMPTY
        return SearchResults(
            songs = libraryDao.searchSongRows(match, limit).map { it.toSong() },
            albums = libraryDao.searchAlbumRows(match, limit).map { it.toAlbum() },
            artists = libraryDao.searchArtistRows(match, limit).map { it.toArtist() },
        )
    }

    private companion object {
        const val SQLITE_MAX_VARIABLES = 900
        const val REDISCOVER_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
