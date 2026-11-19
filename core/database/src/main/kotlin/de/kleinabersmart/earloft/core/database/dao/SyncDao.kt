package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import de.kleinabersmart.earloft.core.database.entity.SongSourceEntity
import de.kleinabersmart.earloft.core.database.entity.SyncActionEntity
import de.kleinabersmart.earloft.core.model.SyncActionType

/** Star of a song source, as last reported by the server or sent by the app. */
data class SourceStarState(val uid: String, val remoteId: String, val songId: Long, val starredAt: Long?)

/** Star of a server album or artist; [id] is the `albumId` or `artistId`. */
data class CollectionStarState(val sourceId: String, val remoteId: String, val id: Long, val starredAt: Long?)

/** Offline changes for servers (concept 6.4) and the favorite state per source. */
@Dao
interface SyncDao {

    @Insert
    suspend fun insert(action: SyncActionEntity): Long

    /** Coalescing: per `(sourceId, actionType, targetKey)` only the last action counts. */
    @Query("DELETE FROM sync_actions WHERE sourceId = :sourceId AND actionType = :actionType AND targetKey = :targetKey")
    suspend fun deleteActions(sourceId: String, actionType: SyncActionType, targetKey: String): Int

    @Query("SELECT * FROM sync_actions ORDER BY createdAt, actionId")
    suspend fun getActions(): List<SyncActionEntity>

    @Query("SELECT COUNT(*) FROM sync_actions")
    suspend fun countActions(): Int

    @Query("DELETE FROM sync_actions WHERE actionId = :actionId")
    suspend fun deleteAction(actionId: Long)

    /** Targets of actions whose execution has already failed once. */
    @Query("SELECT targetKey FROM sync_actions WHERE sourceId = :sourceId AND actionType = :actionType AND attempts > 0")
    suspend fun getRetriedTargets(sourceId: String, actionType: SyncActionType): List<String>

    @Query("UPDATE sync_actions SET attempts = attempts + 1 WHERE actionId = :actionId")
    suspend fun incrementAttempts(actionId: Long)

    // --- Favorites ---

    @Query("UPDATE songs SET isFavorite = :favorite, favoriteChangedAt = :changedAt WHERE songId = :songId")
    suspend fun setSongFavorite(songId: Long, favorite: Boolean, changedAt: Long)

    @Query("SELECT isFavorite FROM songs WHERE songId = :songId")
    suspend fun isSongFavorite(songId: Long): Boolean?

    @Query(
        """
        SELECT ss.* FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.type NOT IN $NOT_SERVER_TYPES
        """,
    )
    suspend fun getServerSources(songId: Long): List<SongSourceEntity>

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    @Query("SELECT uid, remoteId, songId, starredAt FROM song_sources WHERE sourceId = :sourceId")
    suspend fun getStarStates(sourceId: String): List<SourceStarState>

    @Query("UPDATE song_sources SET starredAt = :starredAt WHERE uid = :uid")
    suspend fun setStarredAt(uid: String, starredAt: Long?)

    /** Does one of the song's sources have a favorite change that hasn't been sent yet? */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_actions sa JOIN song_sources ss ON ss.uid = sa.targetKey
            WHERE sa.actionType = 'SET_FAVORITE' AND ss.songId = :songId
        )
        """,
    )
    suspend fun hasPendingFavorite(songId: Long): Boolean

    // --- Favorites of albums and artists ---

    @Query("UPDATE albums SET isFavorite = :favorite, favoriteChangedAt = :changedAt WHERE albumId = :albumId")
    suspend fun setAlbumFavorite(albumId: Long, favorite: Boolean, changedAt: Long)

    @Query("SELECT isFavorite FROM albums WHERE albumId = :albumId")
    suspend fun isAlbumFavorite(albumId: Long): Boolean?

    @Query(
        """
        SELECT als.sourceId, als.remoteId, als.albumId AS id, als.starredAt FROM album_sources als
        JOIN sources src ON src.sourceId = als.sourceId
        WHERE als.albumId = :albumId AND src.type NOT IN $NOT_SERVER_TYPES
        """,
    )
    suspend fun getAlbumServerSources(albumId: Long): List<CollectionStarState>

    @Query("SELECT sourceId, remoteId, albumId AS id, starredAt FROM album_sources WHERE sourceId = :sourceId")
    suspend fun getAlbumStarStates(sourceId: String): List<CollectionStarState>

    @Query("SELECT sourceId, remoteId, albumId AS id, starredAt FROM album_sources WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun getAlbumStarState(sourceId: String, remoteId: String): CollectionStarState?

    @Query("UPDATE album_sources SET starredAt = :starredAt WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun setAlbumStarredAt(sourceId: String, remoteId: String, starredAt: Long?)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_actions sa
            JOIN album_sources als ON als.sourceId = sa.sourceId AND als.remoteId = sa.targetKey
            WHERE sa.actionType = 'SET_ALBUM_FAVORITE' AND als.albumId = :albumId
        )
        """,
    )
    suspend fun hasPendingAlbumFavorite(albumId: Long): Boolean

    @Query("UPDATE artists SET isFavorite = :favorite, favoriteChangedAt = :changedAt WHERE artistId = :artistId")
    suspend fun setArtistFavorite(artistId: Long, favorite: Boolean, changedAt: Long)

    @Query("SELECT isFavorite FROM artists WHERE artistId = :artistId")
    suspend fun isArtistFavorite(artistId: Long): Boolean?

    @Query(
        """
        SELECT ars.sourceId, ars.remoteId, ars.artistId AS id, ars.starredAt FROM artist_sources ars
        JOIN sources src ON src.sourceId = ars.sourceId
        WHERE ars.artistId = :artistId AND src.type NOT IN $NOT_SERVER_TYPES
        """,
    )
    suspend fun getArtistServerSources(artistId: Long): List<CollectionStarState>

    @Query("SELECT sourceId, remoteId, artistId AS id, starredAt FROM artist_sources WHERE sourceId = :sourceId")
    suspend fun getArtistStarStates(sourceId: String): List<CollectionStarState>

    @Query("SELECT sourceId, remoteId, artistId AS id, starredAt FROM artist_sources WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun getArtistStarState(sourceId: String, remoteId: String): CollectionStarState?

    @Query("UPDATE artist_sources SET starredAt = :starredAt WHERE sourceId = :sourceId AND remoteId = :remoteId")
    suspend fun setArtistStarredAt(sourceId: String, remoteId: String, starredAt: Long?)

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_actions sa
            JOIN artist_sources ars ON ars.sourceId = sa.sourceId AND ars.remoteId = sa.targetKey
            WHERE sa.actionType = 'SET_ARTIST_FAVORITE' AND ars.artistId = :artistId
        )
        """,
    )
    suspend fun hasPendingArtistFavorite(artistId: Long): Boolean
}
