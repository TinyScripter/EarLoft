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
    SELECT p.playlistId, p.name, p.boundSourceId, src.name AS serverName, p.unknownSongs,
           COUNT(s.songId) AS songCount, COALESCE(SUM(s.durationMs), 0) AS durationMs
    FROM playlists p
    LEFT JOIN sources src ON src.sourceId = p.boundSourceId
    LEFT JOIN playlist_entries e ON e.playlistId = p.playlistId
    LEFT JOIN songs s ON s.songId = e.songId AND $SONG_VISIBLE
"""

/** Playlists (concept 5.4); local ones and those linked to exactly one server. */
@Dao
interface PlaylistDao {

    @Query("$SELECT_PLAYLIST_ROWS GROUP BY p.playlistId ORDER BY p.name COLLATE NOCASE")
    fun observePlaylistRows(): Flow<List<PlaylistRow>>

    @Query("$SELECT_PLAYLIST_ROWS WHERE p.playlistId = :playlistId GROUP BY p.playlistId")
    fun observePlaylistRow(playlistId: Long): Flow<PlaylistRow?>

    /** `missingOnServer`: the song has no source on the server the playlist is linked to. */
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

    /** After sending: remembers the server's state (conflict detection). @return 0 if the playlist has been deleted in the meantime. */
    @Query("UPDATE playlists SET remotePlaylistId = :remoteId, remoteUpdatedAt = :remoteUpdatedAt WHERE playlistId = :playlistId")
    suspend fun setRemoteState(playlistId: Long, remoteId: String, remoteUpdatedAt: Long): Int

    /**
     * The server state [changedAt]; [remoteUpdatedAt] `null` if it was adopted incomplete, then [unknownSongs] are missing (see
     * `PlaylistSyncer.applyRemote`).
     */
    @Query(
        "UPDATE playlists SET name = :name, remoteUpdatedAt = :remoteUpdatedAt, updatedAt = :changedAt, unknownSongs = :unknownSongs " +
            "WHERE playlistId = :playlistId",
    )
    suspend fun applyRemote(playlistId: Long, name: String, changedAt: Long, remoteUpdatedAt: Long?, unknownSongs: Int)

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlists WHERE boundSourceId = :sourceId")
    suspend fun deleteBoundTo(sourceId: String)

    // --- Entries ---

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

    @Query("SELECT entryId FROM playlist_entries WHERE playlistId = :playlistId ORDER BY position, entryId")
    suspend fun getEntryIds(playlistId: Long): List<Long>

    /**
     * The remote IDs of the entries on [sourceId] in playlist order; songs missing there are dropped.
     * If a server returns a song twice, the same source is always chosen.
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
