package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity

/** Lookups und Schreibzugriffe für die Deduplizierung beim Bibliotheks-Abgleich. */
@Dao
interface MergeDao {

    // --- Künstler ---

    @Query("SELECT * FROM artists WHERE mbid = :mbid LIMIT 1")
    suspend fun findArtistByMbid(mbid: String): ArtistEntity?

    @Query("SELECT * FROM artists WHERE normName = :normName ORDER BY artistId LIMIT 1")
    suspend fun findArtistByNormName(normName: String): ArtistEntity?

    @Insert
    suspend fun insertArtist(artist: ArtistEntity): Long

    @Update
    suspend fun updateArtist(artist: ArtistEntity)

    // --- Alben ---

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

    // --- Song-Quellen ---

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    @Query("SELECT COUNT(*) FROM song_sources WHERE songId = :songId")
    suspend fun countSourcesOfSong(songId: Long): Int

    @Insert
    suspend fun insertSongSource(songSource: SongSourceEntity)

    @Update
    suspend fun updateSongSource(songSource: SongSourceEntity)

    /**
     * Die Datei auf dem Server hat sich geändert: fertigen Download neu laden. Bis dahin wird
     * gestreamt; die alte Datei wird beim Fertigstellen ersetzt.
     */
    @Query("UPDATE downloads SET state = 'QUEUED', bytesDone = 0 WHERE sourceUid = :uid AND state = 'DONE'")
    suspend fun requeueDownload(uid: String)

    @Query("UPDATE song_sources SET seenInGeneration = :generation WHERE uid = :uid")
    suspend fun markSeen(uid: String, generation: Long)

    /** Mark-and-Sweep: Quellen, die beim letzten Vollabgleich nicht mehr gesehen wurden. */
    @Query("DELETE FROM song_sources WHERE sourceId = :sourceId AND seenInGeneration < :generation")
    suspend fun deleteStaleSongSources(sourceId: String, generation: Long): Int
}
