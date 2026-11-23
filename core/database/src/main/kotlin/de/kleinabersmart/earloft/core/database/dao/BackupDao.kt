package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity
import de.kleinabersmart.earloft.core.model.FavoriteKind

/** Columns of a song together with its key from the metadata (see [KeyedSong]). */
private const val KEYED_SONG =
    "s.songId AS songId, a.normAlbumArtist AS albumArtist, a.normTitle AS album, " +
        "s.discNumber AS disc, s.trackNumber AS track, s.normTitle AS title, s.albumId AS albumId, a.year AS year"

/**
 * Reading and applying the music user data via keys that still match on a new device (backup, review K9):
 * songs via their metadata, albums via album artist and title, local playlists via `backupKey`.
 */
@Dao
abstract class BackupDao {

    // --- Songs ---

    /** All songs of the albums with this artist and title, of every year (same normalization as when merging); the caller decides which one matches. */
    @Query("SELECT $KEYED_SONG FROM songs s JOIN albums a ON a.albumId = s.albumId WHERE a.normAlbumArtist = :albumArtist AND a.normTitle = :album")
    abstract suspend fun songsOfAlbum(albumArtist: String, album: String): List<KeyedSong>

    @Query("SELECT songId, isFavorite, favoriteChangedAt FROM songs WHERE songId IN (:songIds)")
    abstract suspend fun favoriteStates(songIds: List<Long>): List<FavoriteState>

    /** Favorites of songs without a server source; the server holds the others and the sync brings them back. */
    @Query(
        "SELECT $KEYED_SONG, s.favoriteChangedAt AS changedAt FROM songs s JOIN albums a ON a.albumId = s.albumId " +
            "WHERE s.isFavorite = 1 AND NOT EXISTS (SELECT 1 FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId " +
            "WHERE ss.songId = s.songId AND src.type NOT IN $NOT_SERVER_TYPES)",
    )
    abstract suspend fun localFavorites(): List<KeyedFavorite>

    // --- Favorites of albums and artists ---

    /** Like [localFavorites] for albums and artists without a server source; for the key see `UserCollectionFavoriteEntity`. */
    @Query(
        "SELECT 'ALBUM' AS kind, a.normAlbumArtist AS artist, a.normTitle AS title, a.favoriteChangedAt AS changedAt, " +
            "COALESCE(a.year, 0) AS year " +
            "FROM albums a WHERE a.isFavorite = 1 AND NOT EXISTS (SELECT 1 FROM album_sources als " +
            "JOIN sources src ON src.sourceId = als.sourceId WHERE als.albumId = a.albumId AND src.type NOT IN $NOT_SERVER_TYPES) " +
            "UNION ALL " +
            "SELECT 'ARTIST' AS kind, ar.normName AS artist, '' AS title, ar.favoriteChangedAt AS changedAt, 0 AS year " +
            "FROM artists ar WHERE ar.isFavorite = 1 AND NOT EXISTS (SELECT 1 FROM artist_sources ars " +
            "JOIN sources src ON src.sourceId = ars.sourceId WHERE ars.artistId = ar.artistId AND src.type NOT IN $NOT_SERVER_TYPES)",
    )
    abstract suspend fun localCollectionFavorites(): List<KeyedCollectionFavorite>

    /** The albums with this artist and title, of every year; the caller picks by year. */
    @Query("SELECT albumId AS id, isFavorite, favoriteChangedAt, year FROM albums WHERE normAlbumArtist = :albumArtist AND normTitle = :album ORDER BY albumId")
    abstract suspend fun albumFavoriteStates(albumArtist: String, album: String): List<CollectionFavoriteState>

    @Query("SELECT artistId AS id, isFavorite, favoriteChangedAt, NULL AS year FROM artists WHERE normName = :name ORDER BY artistId LIMIT 1")
    abstract suspend fun artistFavoriteState(name: String): CollectionFavoriteState?

    // --- Listening history ---

    @Query(
        "SELECT $KEYED_SONG, pe.playedAt AS playedAt, pe.listenedMs AS listenedMs, pe.id AS eventId FROM play_events pe " +
            "JOIN songs s ON s.songId = pe.songId JOIN albums a ON a.albumId = s.albumId",
    )
    abstract suspend fun playEvents(): List<KeyedPlayEvent>

    /**
     * Like [playEvents], only those added after the event [afterId]: IDs only grow (`AUTOINCREMENT`), and events are only
     * removed together with their song.
     */
    @Query(
        "SELECT $KEYED_SONG, pe.playedAt AS playedAt, pe.listenedMs AS listenedMs, pe.id AS eventId FROM play_events pe " +
            "JOIN songs s ON s.songId = pe.songId JOIN albums a ON a.albumId = s.albumId WHERE pe.id > :afterId",
    )
    abstract suspend fun playEventsAfter(afterId: Long): List<KeyedPlayEvent>

    @Query("SELECT playedAt FROM play_events WHERE songId = :songId")
    abstract suspend fun playedAt(songId: Long): List<Long>

    @Insert
    abstract suspend fun insertPlayEvents(events: List<PlayEventEntity>)

    // --- Local playlists ---

    @Query("SELECT * FROM playlists WHERE boundSourceId IS NULL")
    abstract suspend fun localPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE backupKey = :backupKey")
    abstract suspend fun playlistByBackupKey(backupKey: String): PlaylistEntity?

    @Query("UPDATE playlists SET backupKey = :backupKey WHERE playlistId = :playlistId AND backupKey IS NULL")
    abstract suspend fun setBackupKey(playlistId: Long, backupKey: String)

    /** Entries of all local playlists in their order. */
    @Query(
        "SELECT pe.playlistId AS playlistId, $KEYED_SONG FROM playlist_entries pe " +
            "JOIN songs s ON s.songId = pe.songId JOIN albums a ON a.albumId = s.albumId " +
            "WHERE pe.playlistId IN (SELECT playlistId FROM playlists WHERE boundSourceId IS NULL) " +
            "ORDER BY pe.playlistId, pe.position, pe.entryId",
    )
    abstract suspend fun localPlaylistEntries(): List<KeyedPlaylistEntry>

    // --- Selection for the watch ---

    @Query("SELECT * FROM wear_selections")
    abstract suspend fun wearSelections(): List<WearSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWearSelection(selection: WearSelectionEntity)

    @Query("SELECT albumId, normAlbumArtist AS albumArtist, normTitle AS album, year FROM albums WHERE albumId IN (:albumIds)")
    abstract suspend fun albumKeys(albumIds: List<Long>): List<KeyedAlbum>

    /** The albums with this artist and title, of every year; the caller picks by year. */
    @Query("SELECT albumId, normAlbumArtist AS albumArtist, normTitle AS album, year FROM albums WHERE normAlbumArtist = :albumArtist AND normTitle = :album ORDER BY albumId")
    abstract suspend fun albumsNamed(albumArtist: String, album: String): List<KeyedAlbum>

    @Query("SELECT * FROM playlists WHERE playlistId IN (:playlistIds)")
    abstract suspend fun playlists(playlistIds: List<Long>): List<PlaylistEntity>

    @Query("SELECT playlistId FROM playlists WHERE boundSourceId = :sourceId AND remotePlaylistId = :remoteId LIMIT 1")
    abstract suspend fun serverPlaylistId(sourceId: String, remoteId: String): Long?
}

/**
 * A song with its cross-device key: album artist, album, disc, track number and title, normalized as
 * when merging, and the [year] of its album ([albumId]). It matches again as soon as the same music (files or server) is on the new device.
 */
data class KeyedSong(
    val songId: Long,
    val albumArtist: String,
    val album: String,
    val disc: Int,
    val track: Int,
    val title: String,
    val albumId: Long,
    val year: Int?,
)

data class FavoriteState(val songId: Long, val isFavorite: Boolean, val favoriteChangedAt: Long)

data class KeyedFavorite(@Embedded val song: KeyedSong, val changedAt: Long)

/** [year] 0: unknown, or an artist. */
data class KeyedCollectionFavorite(val kind: FavoriteKind, val artist: String, val title: String, val changedAt: Long, val year: Int)

/** [id] is the `albumId` or `artistId`; [year] only for albums. */
data class CollectionFavoriteState(val id: Long, val isFavorite: Boolean, val favoriteChangedAt: Long, val year: Int?)

data class KeyedPlayEvent(@Embedded val song: KeyedSong, val playedAt: Long, val listenedMs: Long, val eventId: Long)

data class KeyedPlaylistEntry(val playlistId: Long, @Embedded val song: KeyedSong)

data class KeyedAlbum(val albumId: Long, val albumArtist: String, val album: String, val year: Int?)
