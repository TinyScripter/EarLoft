package keinabersmart.kleinaberlaut.core.data.repository

import keinabersmart.kleinaberlaut.core.database.dao.LibraryDao
import keinabersmart.kleinaberlaut.core.model.Album
import keinabersmart.kleinaberlaut.core.model.AlbumDetails
import keinabersmart.kleinaberlaut.core.model.Artist
import keinabersmart.kleinaberlaut.core.model.ArtistDetails
import keinabersmart.kleinaberlaut.core.model.SearchResults
import keinabersmart.kleinaberlaut.core.model.Song
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
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

    fun recentlyAddedAlbums(limit: Int = 20): Flow<List<Album>> =
        libraryDao.observeRecentlyAddedAlbumRows(limit).map { rows -> rows.map { it.toAlbum() } }

    fun recentlyPlayedSongs(limit: Int = 20): Flow<List<Song>> =
        libraryDao.observeRecentlyPlayedSongRows(limit).map { rows -> rows.map { it.toSong() } }

    /** Liefert die Songs in der Reihenfolge von [songIds]; unbekannte IDs fallen weg. */
    suspend fun songs(songIds: List<Long>): List<Song> {
        val byId = songIds.distinct()
            .chunked(SQLITE_MAX_VARIABLES)
            .flatMap { libraryDao.getSongRows(it) }
            .associate { it.songId to it.toSong() }
        return songIds.mapNotNull { byId[it] }
    }

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
    }
}
