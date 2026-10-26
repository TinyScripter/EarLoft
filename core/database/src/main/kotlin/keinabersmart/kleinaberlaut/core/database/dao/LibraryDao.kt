package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import keinabersmart.kleinaberlaut.core.database.entity.AlbumArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.AlbumEntity
import keinabersmart.kleinaberlaut.core.database.entity.ArtistEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongArtistCrossRef
import keinabersmart.kleinaberlaut.core.database.entity.SongEntity
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
abstract class LibraryDao {

    @Insert
    abstract suspend fun insertArtist(artist: ArtistEntity): Long

    @Insert
    abstract suspend fun insertAlbum(album: AlbumEntity): Long

    @Insert
    abstract suspend fun insertSong(song: SongEntity): Long

    @Insert
    abstract suspend fun insertSongSource(songSource: SongSourceEntity)

    @Insert
    abstract suspend fun insertSongArtist(crossRef: SongArtistCrossRef)

    @Insert
    abstract suspend fun insertAlbumArtist(crossRef: AlbumArtistCrossRef)

    @Query("SELECT * FROM albums ORDER BY sortTitle")
    abstract fun observeAlbums(): Flow<List<AlbumEntity>>

    @Query("SELECT * FROM artists ORDER BY sortName")
    abstract fun observeArtists(): Flow<List<ArtistEntity>>

    @Query("SELECT * FROM songs WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    abstract fun observeSongsOfAlbum(albumId: Long): Flow<List<SongEntity>>

    @Query("SELECT * FROM song_sources WHERE songId = :songId")
    abstract suspend fun getSourcesOfSong(songId: Long): List<SongSourceEntity>

    @Query("DELETE FROM songs WHERE songId NOT IN (SELECT songId FROM song_sources)")
    protected abstract suspend fun deleteSongsWithoutSources(): Int

    @Query("DELETE FROM albums WHERE albumId NOT IN (SELECT albumId FROM songs)")
    protected abstract suspend fun deleteAlbumsWithoutSongs(): Int

    @Query(
        """
        DELETE FROM artists
        WHERE artistId NOT IN (SELECT artistId FROM song_artists)
          AND artistId NOT IN (SELECT artistId FROM album_artists)
        """,
    )
    protected abstract suspend fun deleteUnreferencedArtists(): Int

    /**
     * Räumt nach dem Entfernen von Quellen bzw. nach einem Mark-and-Sweep-Abgleich auf:
     * Songs ohne Quelle, danach leere Alben und nicht mehr referenzierte Künstler.
     */
    @Transaction
    open suspend fun cleanupOrphans() {
        deleteSongsWithoutSources()
        deleteAlbumsWithoutSongs()
        deleteUnreferencedArtists()
    }
}
