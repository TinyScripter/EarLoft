package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntity
import de.kleinabersmart.earloft.core.database.entity.PlaylistEntryEntity
import de.kleinabersmart.earloft.core.database.model.PlaylistEntryRow
import de.kleinabersmart.earloft.core.database.model.PlaylistRow
import kotlinx.coroutines.flow.Flow

private const val SELECT_PLAYLIST_ROWS = """
    SELECT p.playlistId, p.name, p.boundSourceId, src.name AS serverName,
           COUNT(s.songId) AS songCount, COALESCE(SUM(s.durationMs), 0) AS durationMs
    FROM playlists p
    LEFT JOIN sources src ON src.sourceId = p.boundSourceId
    LEFT JOIN playlist_entries e ON e.playlistId = p.playlistId
    LEFT JOIN songs s ON s.songId = e.songId AND $SONG_VISIBLE
"""

/** Playlists (Konzept 5.4); lokale und mit genau einem Server verknüpfte. */
@Dao
interface PlaylistDao {

    @Query("$SELECT_PLAYLIST_ROWS GROUP BY p.playlistId ORDER BY p.name COLLATE NOCASE")
    fun observePlaylistRows(): Flow<List<PlaylistRow>>

    @Query("$SELECT_PLAYLIST_ROWS WHERE p.playlistId = :playlistId GROUP BY p.playlistId")
    fun observePlaylistRow(playlistId: Long): Flow<PlaylistRow?>

    /** `missingOnServer`: Der Song hat keine Quelle auf dem Server, mit dem die Playlist verknüpft ist. */
    @Query(
        """
        SELECT e.entryId, s.songId, s.title, s.artistDisplay, s.albumId, a.title AS albumTitle,
               s.durationMs, s.discNumber, s.trackNumber, s.isFavorite,
               (p.boundSourceId IS NOT NULL AND NOT EXISTS(
                   SELECT 1 FROM song_sources ss WHERE ss.songId = s.songId AND ss.sourceId = p.boundSourceId
               )) AS missingOnServer
        FROM playlist_entries e
        JOIN playlists p ON p.playlistId = e.playlistId
        JOIN songs s ON s.songId = e.songId AND $SONG_VISIBLE
        JOIN albums a ON a.albumId = s.albumId
        WHERE e.playlistId = :playlistId
        ORDER BY e.position, e.entryId
        """,
    )
    fun observeEntryRows(playlistId: Long): Flow<List<PlaylistEntryRow>>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("SELECT * FROM playlists WHERE playlistId = :playlistId")
    suspend fun getPlaylist(playlistId: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE boundSourceId = :sourceId")
    suspend fun getBoundTo(sourceId: String): List<PlaylistEntity>

    @Query("UPDATE playlists SET name = :name, updatedAt = :updatedAt WHERE playlistId = :playlistId")
    suspend fun rename(playlistId: Long, name: String, updatedAt: Long)

    @Query("UPDATE playlists SET updatedAt = :updatedAt WHERE playlistId = :playlistId")
    suspend fun touch(playlistId: Long, updatedAt: Long)

    /** Nach dem Senden: Stand des Servers merken (Konflikterkennung). @return 0, wenn die Playlist inzwischen gelöscht ist. */
    @Query("UPDATE playlists SET remotePlaylistId = :remoteId, remoteUpdatedAt = :remoteUpdatedAt WHERE playlistId = :playlistId")
    suspend fun setRemoteState(playlistId: Long, remoteId: String, remoteUpdatedAt: Long): Int

    @Query("UPDATE playlists SET name = :name, remoteUpdatedAt = :remoteUpdatedAt, updatedAt = :remoteUpdatedAt WHERE playlistId = :playlistId")
    suspend fun applyRemote(playlistId: Long, name: String, remoteUpdatedAt: Long)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlists WHERE boundSourceId = :sourceId")
    suspend fun deleteBoundTo(sourceId: String)

    // --- Einträge ---

    @Insert
    suspend fun insertEntries(entries: List<PlaylistEntryEntity>)

    @Query("SELECT COALESCE(MAX(position) + 1, 0) FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun nextPosition(playlistId: Long): Int

    @Query("DELETE FROM playlist_entries WHERE entryId = :entryId")
    suspend fun deleteEntry(entryId: Long)

    @Query("UPDATE playlist_entries SET position = :position WHERE entryId = :entryId AND playlistId = :playlistId")
    suspend fun setPosition(playlistId: Long, entryId: Long, position: Int)

    @Query("DELETE FROM playlist_entries WHERE playlistId = :playlistId")
    suspend fun deleteEntries(playlistId: Long)

    @Query("SELECT songId FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position, entryId")
    suspend fun getSongIds(playlistId: Long): List<Long>

    /**
     * Die Remote-IDs der Einträge auf [sourceId] in Playlist-Reihenfolge; Songs, die dort fehlen, entfallen.
     * Liefert ein Server einen Song doppelt, wird stets dieselbe Quelle gewählt.
     */
    @Query(
        """
        SELECT ss.remoteId FROM playlist_entries e
        JOIN song_sources ss ON ss.uid = (SELECT s.uid FROM song_sources s
                WHERE s.songId = e.songId AND s.sourceId = :sourceId ORDER BY s.uid LIMIT 1)
        WHERE e.playlistId = :playlistId
        ORDER BY e.position, e.entryId
        """,
    )
    suspend fun getRemoteSongIds(playlistId: Long, sourceId: String): List<String>
}
