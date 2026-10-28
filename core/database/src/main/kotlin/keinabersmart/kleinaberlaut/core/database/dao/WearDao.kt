package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
import keinabersmart.kleinaberlaut.core.database.model.SourceSongIdRow
import keinabersmart.kleinaberlaut.core.database.model.WearSongArtistRow
import keinabersmart.kleinaberlaut.core.database.model.WearSongRow
import keinabersmart.kleinaberlaut.core.database.model.WearSourceRow
import kotlinx.coroutines.flow.Flow

@Dao
interface WearDao {
    /** In der Reihenfolge des Hinzufügens (bestimmt die Download-Reihenfolge auf der Uhr). */
    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    fun observeSelections(): Flow<List<WearSelectionEntity>>

    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    suspend fun getSelections(): List<WearSelectionEntity>

    @Query("SELECT * FROM wear_selections WHERE `key` = :key")
    suspend fun getSelection(key: String): WearSelectionEntity?

    @Upsert
    suspend fun upsert(selection: WearSelectionEntity)

    @Query("DELETE FROM wear_selections WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM wear_selections")
    suspend fun deleteAll()

    // --- Handy: Auswahlen auflösen (Wear-Konzept 5) ---

    /**
     * Entfernt Auswahlen, deren Album bzw. Playlist es nicht mehr gibt (Abgleich, Playlist gelöscht,
     * Server entfernt). Nur am Handy – auf der Uhr zeigt `refId` auf deren eigene Geräte-Playlist.
     */
    @Query(
        """
        DELETE FROM wear_selections
        WHERE (kind = 'ALBUM' AND refId NOT IN (SELECT albumId FROM albums))
           OR (kind = 'PLAYLIST' AND refId NOT IN (SELECT playlistId FROM playlists))
        """,
    )
    suspend fun deleteDangling(): Int

    @Query("SELECT title FROM albums WHERE albumId = :albumId")
    suspend fun getAlbumTitle(albumId: Long): String?

    @Query("SELECT songId FROM songs WHERE albumId = :albumId ORDER BY discNumber, trackNumber, title")
    suspend fun getAlbumSongIds(albumId: Long): List<Long>

    /** Wie die Favoriten-Ansicht: zuletzt favorisierte zuerst. */
    @Query("SELECT songId FROM songs WHERE isFavorite ORDER BY favoriteChangedAt DESC")
    suspend fun getFavoriteSongIds(): List<Long>

    /** Max. ~900 IDs pro Aufruf (SQLite-Variablenlimit). */
    @Query(
        """
        SELECT s.songId, s.title, s.artistDisplay, s.discNumber, s.trackNumber, s.durationMs,
               COALESCE(s.year, a.year) AS year, s.genre, s.mbid,
               a.title AS albumTitle, a.albumArtistDisplay, a.isCompilation, a.mbid AS albumMbid
        FROM songs s JOIN albums a ON a.albumId = s.albumId
        WHERE s.songId IN (:songIds)
        """,
    )
    suspend fun getSongs(songIds: List<Long>): List<WearSongRow>

    @Query(
        """
        SELECT sa.songId, ar.name FROM song_artists sa JOIN artists ar ON ar.artistId = sa.artistId
        WHERE sa.songId IN (:songIds)
        ORDER BY sa.songId, sa.position
        """,
    )
    suspend fun getSongArtists(songIds: List<Long>): List<WearSongArtistRow>

    /** Alle Quellen der Songs samt fertigem Download (für die Datei, die das Handy der Uhr schicken kann). */
    @Query(
        """
        SELECT ss.uid, ss.songId, ss.sourceId, ss.remoteId, ss.coverRemoteId, src.type AS sourceType,
               src.priority AS sourcePriority, ss.mimeType, ss.bitRate, ss.sizeBytes,
               ss.rgTrackGainDb, ss.rgTrackPeak, ss.rgAlbumGainDb, ss.rgAlbumPeak,
               d.filePath AS downloadPath, d.bytesTotal AS downloadBytes
        FROM song_sources ss
        JOIN sources src ON src.sourceId = ss.sourceId
        LEFT JOIN downloads d ON d.sourceUid = ss.uid AND d.state = 'DONE' AND d.filePath IS NOT NULL
        WHERE ss.songId IN (:songIds)
        """,
    )
    suspend fun getSources(songIds: List<Long>): List<WearSourceRow>

    // --- Uhr ---

    @Query("SELECT uid, songId FROM song_sources WHERE uid IN (:uids)")
    suspend fun getSongIdsOfSources(uids: List<String>): List<SourceSongIdRow>
}
