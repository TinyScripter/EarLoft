package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import de.kleinabersmart.earloft.core.database.entity.PlayEventEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.WearSelectionEntity

/** Spalten eines Songs samt seinem Schlüssel aus den Metadaten (siehe [KeyedSong]). */
private const val KEYED_SONG =
    "s.songId AS songId, a.normAlbumArtist AS albumArtist, a.normTitle AS album, " +
        "s.discNumber AS disc, s.trackNumber AS track, s.normTitle AS title"

/**
 * Lesen und Anwenden der Musik-Nutzerdaten über Schlüssel, die auf einem neuen Gerät noch passen (Backup, Review K9):
 * Songs über ihre Metadaten, Alben über Album-Künstler und Titel, lokale Playlists über `backupKey`.
 */
@Dao
abstract class BackupDao {

    // --- Songs ---

    /** Alle Songs eines Albums (gleiche Normalisierung wie beim Zusammenführen); welcher passt, entscheidet der Aufrufer. */
    @Query("SELECT $KEYED_SONG FROM songs s JOIN albums a ON a.albumId = s.albumId WHERE a.normAlbumArtist = :albumArtist AND a.normTitle = :album")
    abstract suspend fun songsOfAlbum(albumArtist: String, album: String): List<KeyedSong>

    @Query("SELECT songId, isFavorite, favoriteChangedAt FROM songs WHERE songId IN (:songIds)")
    abstract suspend fun favoriteStates(songIds: List<Long>): List<FavoriteState>

    /** Favoriten von Songs ohne Server-Quelle; die anderen hält der Server und bringt der Abgleich zurück. */
    @Query(
        "SELECT $KEYED_SONG, s.favoriteChangedAt AS changedAt FROM songs s JOIN albums a ON a.albumId = s.albumId " +
            "WHERE s.isFavorite = 1 AND NOT EXISTS (SELECT 1 FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId " +
            "WHERE ss.songId = s.songId AND src.type NOT IN $NOT_SERVER_TYPES)",
    )
    abstract suspend fun localFavorites(): List<KeyedFavorite>

    // --- Hörverlauf ---

    @Query(
        "SELECT $KEYED_SONG, pe.playedAt AS playedAt, pe.listenedMs AS listenedMs FROM play_events pe " +
            "JOIN songs s ON s.songId = pe.songId JOIN albums a ON a.albumId = s.albumId",
    )
    abstract suspend fun playEvents(): List<KeyedPlayEvent>

    @Query("SELECT playedAt FROM play_events WHERE songId = :songId")
    abstract suspend fun playedAt(songId: Long): List<Long>

    @Insert
    abstract suspend fun insertPlayEvents(events: List<PlayEventEntity>)

    // --- Lokale Playlists ---

    @Query("SELECT * FROM playlists WHERE boundSourceId IS NULL")
    abstract suspend fun localPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE backupKey = :backupKey")
    abstract suspend fun playlistByBackupKey(backupKey: String): PlaylistEntity?

    @Query("UPDATE playlists SET backupKey = :backupKey WHERE playlistId = :playlistId AND backupKey IS NULL")
    abstract suspend fun setBackupKey(playlistId: Long, backupKey: String)

    /** Einträge aller lokalen Playlists in ihrer Reihenfolge. */
    @Query(
        "SELECT pe.playlistId AS playlistId, $KEYED_SONG FROM playlist_entries pe " +
            "JOIN songs s ON s.songId = pe.songId JOIN albums a ON a.albumId = s.albumId " +
            "WHERE pe.playlistId IN (SELECT playlistId FROM playlists WHERE boundSourceId IS NULL) " +
            "ORDER BY pe.playlistId, pe.position, pe.entryId",
    )
    abstract suspend fun localPlaylistEntries(): List<KeyedPlaylistEntry>

    // --- Auswahl für die Uhr ---

    @Query("SELECT * FROM wear_selections")
    abstract suspend fun wearSelections(): List<WearSelectionEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insertWearSelection(selection: WearSelectionEntity)

    @Query("SELECT albumId, normAlbumArtist AS albumArtist, normTitle AS album FROM albums WHERE albumId IN (:albumIds)")
    abstract suspend fun albumKeys(albumIds: List<Long>): List<KeyedAlbum>

    @Query("SELECT albumId FROM albums WHERE normAlbumArtist = :albumArtist AND normTitle = :album ORDER BY albumId LIMIT 1")
    abstract suspend fun albumId(albumArtist: String, album: String): Long?

    @Query("SELECT * FROM playlists WHERE playlistId IN (:playlistIds)")
    abstract suspend fun playlists(playlistIds: List<Long>): List<PlaylistEntity>

    @Query("SELECT playlistId FROM playlists WHERE boundSourceId = :sourceId AND remotePlaylistId = :remoteId LIMIT 1")
    abstract suspend fun serverPlaylistId(sourceId: String, remoteId: String): Long?
}

/**
 * Ein Song mit seinem geräteübergreifenden Schlüssel: Album-Künstler, Album, CD, Titelnummer und Titel, normalisiert wie
 * beim Zusammenführen. Er passt wieder, sobald dieselbe Musik (Dateien oder Server) auf dem neuen Gerät ist.
 */
data class KeyedSong(
    val songId: Long,
    val albumArtist: String,
    val album: String,
    val disc: Int,
    val track: Int,
    val title: String,
)

data class FavoriteState(val songId: Long, val isFavorite: Boolean, val favoriteChangedAt: Long)

data class KeyedFavorite(@Embedded val song: KeyedSong, val changedAt: Long)

data class KeyedPlayEvent(@Embedded val song: KeyedSong, val playedAt: Long, val listenedMs: Long)

data class KeyedPlaylistEntry(val playlistId: Long, @Embedded val song: KeyedSong)

data class KeyedAlbum(val albumId: Long, val albumArtist: String, val album: String)
