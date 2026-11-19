package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.AlbumSourceEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongGenreEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity

/** Lookups and writes for deduplication during the library sync. */
@Dao
interface MergeDao {

    // --- Artists ---

    @Query("SELECT * FROM artists WHERE mbid = :mbid LIMIT 1")
    suspend fun findArtistByMbid(mbid: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE normName = :normName ORDER BY artistId LIMIT 1")
    suspend fun findArtistByNormName(normName: String): ArtistEntity?

    @Insert
    suspend fun insertArtist(artist: ArtistEntity): Long

    @Update
    suspend fun updateArtist(artist: ArtistEntity)

    // --- Albums ---

    @Query("SELECT * FROM albums WHERE mbid = :mbid LIMIT 1")
    suspend fun findAlbumByMbid(mbid: String): AlbumEntity?

    @Query("SELECT * FROM albums WHERE normAlbumArtist = :normAlbumArtist AND normTitle = :normTitle")
    suspend fun findAlbums(normAlbumArtist: String, normTitle: String): List<AlbumEntity>

    @Insert
    suspend fun insertAlbum(album: AlbumEntity): Long

    @Update
    suspend fun updateAlbum(album: AlbumEntity)

    @Query("UPDATE albums SET coverSourceUid = :uid WHERE albumId = :albumId AND coverSourceUid IS NULL")
    suspend fun setAlbumCoverIfMissing(albumId: Long, uid: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumArtist(crossRef: AlbumArtistCrossRef)

    // --- Songs ---

    @Query("SELECT * FROM songs WHERE songId = :songId")
    suspend fun getSong(songId: Long): SongEntity?

    @Query("SELECT * FROM songs WHERE albumId = :albumId AND mbid = :mbid LIMIT 1")
    suspend fun findSongByMbid(albumId: Long, mbid: String): SongEntity?

    @Query(
        """
        SELECT * FROM songs
        WHERE albumId = :albumId AND discNumber = :discNumber AND trackNumber = :trackNumber
          AND normTitle = :normTitle
        LIMIT 1
        """,
    )
    suspend fun findSongByPosition(albumId: Long, discNumber: Int, trackNumber: Int, normTitle: String): SongEntity?

    @Query("SELECT * FROM songs WHERE albumId = :albumId AND normTitle = :normTitle")
    suspend fun findSongsByTitle(albumId: Long, normTitle: String): List<SongEntity>

    @Insert
    suspend fun insertSong(song: SongEntity): Long

    @Update
    suspend fun updateSong(song: SongEntity)

    @Query("DELETE FROM song_artists WHERE songId = :songId")
    suspend fun deleteSongArtists(songId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongArtist(crossRef: SongArtistCrossRef)

    @Query("DELETE FROM song_genres WHERE songId = :songId")
    suspend fun deleteSongGenres(songId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongGenres(genres: List<SongGenreEntity>)

    // --- Song sources ---

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    @Query("SELECT COUNT(*) FROM song_sources WHERE songId = :songId")
    suspend fun countSourcesOfSong(songId: Long): Int

    @Query("SELECT * FROM song_sources WHERE songId = :songId")
    suspend fun getSourcesOfSong(songId: Long): List<SongSourceEntity>

    /** The song already has a source from [sourceId] other than [exceptUid]. */
    @Query("SELECT EXISTS (SELECT 1 FROM song_sources WHERE songId = :songId AND sourceId = :sourceId AND uid != :exceptUid)")
    suspend fun hasOtherSourceFrom(songId: Long, sourceId: String, exceptUid: String): Boolean

    @Insert
    suspend fun insertSongSource(songSource: SongSourceEntity)

    @Update
    suspend fun updateSongSource(songSource: SongSourceEntity)

    /**
     * The file on the server has changed: re-download the finished download. Until then it is
     * streamed; the old file is replaced on completion.
     */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE sourceUid = :uid AND state = 'DONE'")
    suspend fun requeueDownload(uid: String)

    @Query("UPDATE song_sources SET seenInGeneration = :generation WHERE uid = :uid")
    suspend fun markSeen(uid: String, generation: Long)

    /** Mark-and-sweep: sources that were no longer seen during the last full sync. */
    @Query("DELETE FROM song_sources WHERE sourceId = :sourceId AND seenInGeneration < :generation")
    suspend fun deleteStaleSongSources(sourceId: String, generation: Long): Int

    // --- Album and artist sources ---

    @Query("SELECT * FROM album_sources WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun getAlbumSource(sourceId: String, remoteId: String): AlbumSourceEntity?

    @Insert
    suspend fun insertAlbumSource(albumSource: AlbumSourceEntity)

    @Update
    suspend fun updateAlbumSource(albumSource: AlbumSourceEntity)

    @Query("DELETE FROM album_sources WHERE sourceId = :sourceId AND seenInGeneration < :generation")
    suspend fun deleteStaleAlbumSources(sourceId: String, generation: Long)

    @Query("SELECT * FROM artist_sources WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun getArtistSource(sourceId: String, remoteId: String): ArtistSourceEntity?

    @Insert
    suspend fun insertArtistSource(artistSource: ArtistSourceEntity)

    @Update
    suspend fun updateArtistSource(artistSource: ArtistSourceEntity)

    @Query("DELETE FROM artist_sources WHERE sourceId = :sourceId AND seenInGeneration < :generation")
    suspend fun deleteStaleArtistSources(sourceId: String, generation: Long)

    /** Artists of the song and its album. */
    @Query(
        """
        SELECT * FROM artists
        WHERE artistId IN (SELECT artistId FROM song_artists WHERE songId = :songId)
           OR artistId IN (SELECT aa.artistId FROM album_artists aa JOIN songs s ON s.albumId = aa.albumId WHERE s.songId = :songId)
        """,
    )
    suspend fun getArtistsOfSong(songId: Long): List<ArtistEntity>
}
