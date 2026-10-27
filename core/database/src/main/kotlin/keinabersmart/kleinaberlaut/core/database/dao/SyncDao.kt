package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import keinabersmart.kleinaberlaut.core.database.entity.SongSourceEntity
import keinabersmart.kleinaberlaut.core.database.entity.SyncActionEntity
import keinabersmart.kleinaberlaut.core.model.SyncActionType

/** Offline-Änderungen für Server (Konzept 6.4) und der Favoriten-Zustand je Quelle. */
@Dao
interface SyncDao {

    @Insert
    suspend fun insert(action: SyncActionEntity): Long

    /** Zusammenfassen: Pro `(sourceId, actionType, targetKey)` gilt nur die letzte Aktion. */
    @Query("DELETE FROM sync_actions WHERE sourceId = :sourceId AND actionType = :actionType AND targetKey = :targetKey")
    suspend fun deleteActions(sourceId: String, actionType: SyncActionType, targetKey: String): Int

    @Query("SELECT * FROM sync_actions ORDER BY createdAt, actionId")
    suspend fun getActions(): List<SyncActionEntity>

    @Query("SELECT COUNT(*) FROM sync_actions")
    suspend fun countActions(): Int

    @Query("DELETE FROM sync_actions WHERE actionId = :actionId")
    suspend fun deleteAction(actionId: Long)

    @Query("UPDATE sync_actions SET attempts = attempts + 1 WHERE actionId = :actionId")
    suspend fun incrementAttempts(actionId: Long)

    // --- Favoriten ---

    @Query("UPDATE songs SET isFavorite = :favorite, favoriteChangedAt = :changedAt WHERE songId = :songId")
    suspend fun setSongFavorite(songId: Long, favorite: Boolean, changedAt: Long)

    @Query("SELECT isFavorite FROM songs WHERE songId = :songId")
    suspend fun isSongFavorite(songId: Long): Boolean?

    @Query(
        """
        SELECT ss.* FROM song_sources ss JOIN sources src ON src.sourceId = ss.sourceId
        WHERE ss.songId = :songId AND src.type = 'SUBSONIC'
        """,
    )
    suspend fun getServerSources(songId: Long): List<SongSourceEntity>

    @Query("SELECT * FROM song_sources WHERE uid = :uid")
    suspend fun getSongSource(uid: String): SongSourceEntity?

    @Query("UPDATE song_sources SET starredAt = :starredAt WHERE uid = :uid")
    suspend fun setStarredAt(uid: String, starredAt: Long?)

    /** Hat eine der Quellen des Songs eine noch nicht gesendete Favoriten-Änderung? */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM sync_actions sa JOIN song_sources ss ON ss.uid = sa.targetKey
            WHERE sa.actionType = 'SET_FAVORITE' AND ss.songId = :songId
        )
        """,
    )
    suspend fun hasPendingFavorite(songId: Long): Boolean
}
