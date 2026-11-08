package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import de.kleinabersmart.earloft.core.database.entity.AlbumArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.AlbumEntity
import de.kleinabersmart.earloft.core.database.entity.ArtistEntity
import de.kleinabersmart.earloft.core.database.entity.SongArtistCrossRef
import de.kleinabersmart.earloft.core.database.entity.SongEntity
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.model.AlbumLookupRow
import de.kleinabersmart.earloft.core.database.model.AlbumRow
import de.kleinabersmart.earloft.core.database.model.ArtistRow
import de.kleinabersmart.earloft.core.database.model.PlayableSourceRow
import de.kleinabersmart.earloft.core.database.model.ReplayGainRow
import de.kleinabersmart.earloft.core.database.model.SongRow
import kotlinx.coroutines.flow.Flow

/**
 * Der Song (Alias `s`) hat mindestens eine Quelle, deren Server eingeschaltet ist. Deaktivierte Server
 * blenden ihre Inhalte aus, ohne etwas zu löschen (Downloads, Favoriten, Playlists bleiben erhalten).
 */
const val SONG_VISIBLE = """EXISTS (
    SELECT 1 FROM song_sources vss JOIN sources vsrc ON vsrc.sourceId = vss.sourceId
    WHERE vss.songId = s.songId AND vsrc.enabled = 1
)"""

private const val SELECT_SONG_ROWS = """
    SELECT s.songId, s.title, s.artistDisplay, s.albumId, a.title AS albumTitle,
           s.durationMs, s.discNumber, s.trackNumber, s.isFavorite
    FROM songs s JOIN albums a ON a.albumId = s.albumId AND $SONG_VISIBLE
"""

private const val SELECT_ALBUM_ROWS = """
    SELECT a.albumId, a.title, a.albumArtistDisplay, a.year, COUNT(s.songId) AS songCount
    FROM albums a JOIN songs s ON s.albumId = a.albumId AND $SONG_VISIBLE
"""

private const val SELECT_ARTIST_ROWS = """
    SELECT ar.artistId, ar.name, COUNT(DISTINCT aa.albumId) AS albumCount
    FROM artists ar JOIN album_artists aa ON aa.artistId = ar.artistId
        AND EXISTS (SELECT 1 FROM songs s WHERE s.albumId = aa.albumId AND $SONG_VISIBLE)
"""

/** Lesender Zugriff auf die Bibliothek für UI und Wiedergabe. */
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

    // --- Listen für die UI ---

    @Query("$SELECT_ALBUM_ROWS GROUP BY a.albumId ORDER BY a.sortTitle COLLATE NOCASE")
    abstract fun observeAlbumRows(): Flow<List<AlbumRow>>

    @Query("$SELECT_ALBUM_ROWS WHERE a.albumId = :albumId GROUP BY a.albumId")
    abstract fun observeAlbumRow(albumId: Long): Flow<AlbumRow?>

    @Query("$SELECT_ARTIST_ROWS GROUP BY ar.artistId ORDER BY ar.sortName COLLATE NOCASE")
    abstract fun observeArtistRows(): Flow<List<ArtistRow>>

    @Query("$SELECT_ARTIST_ROWS WHERE ar.artistId = :artistId GROUP BY ar.artistId")
    abstract fun observeArtistRow(artistId: Long): Flow<ArtistRow?>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN album_artists aa ON aa.albumId = a.albumId
        WHERE aa.artistId = :artistId
        GROUP BY a.albumId
        ORDER BY a.year IS NULL, a.year, a.sortTitle COLLATE NOCASE
        """,
    )
    abstract fun observeAlbumRowsOfArtist(artistId: Long): Flow<List<AlbumRow>>

    /** Welche Künstler zu welchem Album gehören (Albumkünstler); die Uhr leitet daraus ihre Künstler ab. */
    @Query("SELECT * FROM album_artists")
    abstract fun observeAlbumArtists(): Flow<List<AlbumArtistCrossRef>>

    @Query("$SELECT_SONG_ROWS ORDER BY s.title COLLATE NOCASE")
    abstract fun observeSongRows(): Flow<List<SongRow>>

    @Query("$SELECT_SONG_ROWS WHERE s.albumId = :albumId ORDER BY s.discNumber, s.trackNumber, s.title")
    abstract fun observeSongRowsOfAlbum(albumId: Long): Flow<List<SongRow>>

    @Query("$SELECT_SONG_ROWS WHERE s.isFavorite ORDER BY s.favoriteChangedAt DESC")
    abstract fun observeFavoriteSongRows(): Flow<List<SongRow>>

    @Query("SELECT isFavorite FROM songs WHERE songId = :songId")
    abstract fun observeIsFavorite(songId: Long): Flow<Boolean?>

    /** Reihenfolge entspricht nicht [songIds]; max. ~900 IDs pro Aufruf (SQLite-Variablenlimit). */
    @Query("$SELECT_SONG_ROWS WHERE s.songId IN (:songIds)")
    abstract suspend fun getSongRows(songIds: List<Long>): List<SongRow>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        GROUP BY a.albumId
        ORDER BY MAX(s.dateAdded) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentlyAddedAlbumRows(limit: Int): Flow<List<AlbumRow>>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN play_events pe ON pe.songId = s.songId
        GROUP BY a.albumId
        ORDER BY MAX(pe.playedAt) DESC
        LIMIT :limit
        """,
    )
    abstract fun observeRecentlyPlayedAlbumRows(limit: Int): Flow<List<AlbumRow>>

    /** Alben, die schon einmal gespielt wurden, zuletzt aber vor [playedBefore]; das älteste zuerst. */
    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN play_events pe ON pe.songId = s.songId
        GROUP BY a.albumId
        HAVING MAX(pe.playedAt) < :playedBefore
        ORDER BY MAX(pe.playedAt)
        LIMIT :limit
        """,
    )
    abstract fun observeRediscoverAlbumRows(playedBefore: Long, limit: Int): Flow<List<AlbumRow>>

    @Query("SELECT COUNT(DISTINCT s.albumId) FROM songs s WHERE $SONG_VISIBLE")
    abstract fun observeAlbumCount(): Flow<Int>

    /** Höchste vergebene `songId` (0 ohne Songs); Ids werden nie wiederverwendet, spätere Songs haben also eine größere. */
    @Query("SELECT COALESCE(MAX(songId), 0) FROM songs")
    abstract suspend fun maxSongId(): Long

    /** Sichtbare Songs, die nach [songId] angelegt wurden. */
    @Query("SELECT COUNT(*) FROM songs s WHERE s.songId > :songId AND $SONG_VISIBLE")
    abstract suspend fun countVisibleSongsAfter(songId: Long): Int

    @Query("$SELECT_ALBUM_ROWS GROUP BY a.albumId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomAlbumRows(limit: Int): List<AlbumRow>

    @Query("$SELECT_ARTIST_ROWS GROUP BY ar.artistId ORDER BY RANDOM() LIMIT :limit")
    abstract suspend fun getRandomArtistRows(limit: Int): List<ArtistRow>

    // --- Volltextsuche (FTS4-MATCH-Ausdruck, z. B. "sig* ros*") ---

    @Query(
        """
        $SELECT_SONG_ROWS
        JOIN songs_fts ON songs_fts.rowid = s.songId
        WHERE songs_fts MATCH :match
        ORDER BY s.title COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchSongRows(match: String, limit: Int): List<SongRow>

    @Query(
        """
        $SELECT_ALBUM_ROWS
        JOIN albums_fts ON albums_fts.rowid = a.albumId
        WHERE albums_fts MATCH :match
        GROUP BY a.albumId
        ORDER BY a.sortTitle COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchAlbumRows(match: String, limit: Int): List<AlbumRow>

    @Query(
        """
        $SELECT_ARTIST_ROWS
        JOIN artists_fts ON artists_fts.rowid = ar.artistId
        WHERE artists_fts MATCH :match
        GROUP BY ar.artistId
        ORDER BY ar.sortName COLLATE NOCASE
        LIMIT :limit
        """,
    )
    abstract suspend fun searchArtistRows(match: String, limit: Int): List<ArtistRow>

    // --- Wiedergabe & Artwork ---

    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.enabled = 1
        """,
    )
    abstract suspend fun getPlayableSources(songId: Long): List<PlayableSourceRow>

    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId IN (:songIds) AND src.enabled = 1
        """,
    )
    abstract suspend fun getPlayableSourcesOfSongs(songIds: List<Long>): List<PlayableSourceRow>

    @Query("SELECT rgTrackGainDb, rgTrackPeak, rgAlbumGainDb, rgAlbumPeak FROM song_sources WHERE uid = :uid")
    abstract suspend fun getReplayGain(uid: String): ReplayGainRow?

    @Query("SELECT uid FROM song_sources WHERE uid IN (:uids)")
    abstract suspend fun getExistingSourceUids(uids: List<String>): List<String>

    @Query("SELECT songId FROM songs WHERE albumId = :albumId")
    abstract suspend fun getSongIdsOfAlbum(albumId: Long): List<Long>

    /** Bevorzugt die Cover-Quelle des Albums, sonst irgendeine Quelle eines Album-Songs. */
    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate
        FROM song_sources ss
        JOIN songs s ON s.songId = ss.songId
        JOIN sources src ON src.sourceId = ss.sourceId
        WHERE s.albumId = :albumId
        ORDER BY ss.uid = (SELECT coverSourceUid FROM albums WHERE albumId = :albumId) DESC
        LIMIT 1
        """,
    )
    abstract suspend fun getCoverSource(albumId: Long): PlayableSourceRow?

    @Query("SELECT albumId, title, albumArtistDisplay AS artist, mbid FROM albums WHERE albumId = :albumId")
    abstract suspend fun getAlbumLookup(albumId: Long): AlbumLookupRow?

    @Query("SELECT name FROM artists WHERE artistId = :artistId")
    abstract suspend fun getArtistName(artistId: Long): String?

    @Query("SELECT * FROM artists WHERE artistId = :artistId")
    abstract suspend fun getArtist(artistId: Long): ArtistEntity?

    @Query("SELECT * FROM songs WHERE songId = :songId")
    abstract suspend fun getSongEntity(songId: Long): SongEntity?

    @Query("SELECT seedColor FROM albums WHERE albumId = :albumId")
    abstract fun observeSeedColor(albumId: Long): Flow<Int?>

    @Query("UPDATE albums SET seedColor = :color WHERE albumId = :albumId")
    abstract suspend fun setSeedColor(albumId: Long, color: Int)

    // --- Aufräumen ---

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

    @Query(
        """
        UPDATE albums SET coverSourceUid = (
            SELECT ss.uid FROM song_sources ss JOIN songs s ON s.songId = ss.songId
            WHERE s.albumId = albums.albumId LIMIT 1
        )
        WHERE coverSourceUid IS NULL OR coverSourceUid NOT IN (SELECT uid FROM song_sources)
        """,
    )
    protected abstract suspend fun repairAlbumCovers()

    /**
     * Räumt nach dem Entfernen von Quellen bzw. nach einem Mark-and-Sweep-Abgleich auf:
     * Songs ohne Quelle, danach leere Alben, nicht mehr referenzierte Künstler und
     * Cover-Verweise auf gelöschte Quellen.
     */
    @Transaction
    open suspend fun cleanupOrphans() {
        deleteSongsWithoutSources()
        deleteAlbumsWithoutSongs()
        deleteUnreferencedArtists()
        repairAlbumCovers()
    }
}
