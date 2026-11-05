package de.kleinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import de.kleinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import de.kleinabersmart.kleinaberlaut.core.model.SourceType
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    /** Legt die Quelle an, ohne Sync-Zustand einer vorhandenen Quelle zu überschreiben. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY priority DESC, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): SourceEntity?

    @Query("SELECT * FROM sources WHERE type = :type")
    suspend fun getByType(type: SourceType): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE type = :type ORDER BY priority DESC, name")
    fun observeByType(type: SourceType): Flow<List<SourceEntity>>

    @Query("UPDATE sources SET syncGeneration = :generation, lastFullSyncAt = :syncedAt WHERE sourceId = :sourceId")
    suspend fun setSyncGeneration(sourceId: String, generation: Long, syncedAt: Long)

    @Query("UPDATE sources SET enabled = :enabled WHERE sourceId = :sourceId")
    suspend fun setEnabled(sourceId: String, enabled: Boolean)

    @Query("UPDATE sources SET castBaseUrl = :castBaseUrl WHERE sourceId = :sourceId")
    suspend fun setCastBaseUrl(sourceId: String, castBaseUrl: String?)

    /** Entfernt per CASCADE auch alle `song_sources`; danach [LibraryDao.cleanupOrphans] aufrufen. */
    @Query("DELETE FROM sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}
