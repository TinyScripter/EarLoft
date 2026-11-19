package de.kleinabersmart.earloft.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import de.kleinabersmart.earloft.core.database.entity.SourceEntity
import de.kleinabersmart.earloft.core.model.SourceType
import kotlinx.coroutines.flow.Flow

/**
 * SQL list of source types that are not a server (`SourceType.isServer == false`). Queries for
 * "any server" filter with `type NOT IN` – so new server types need no adjustment.
 */
internal const val NOT_SERVER_TYPES = "('LOCAL', 'PHONE')"

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    /** Inserts the source without overwriting the sync state of an existing source. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY priority DESC, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): SourceEntity?

    /** All music servers of whatever type ([SourceType.isServer], see [NOT_SERVER_TYPES]). */
    @Query("SELECT * FROM sources WHERE type NOT IN $NOT_SERVER_TYPES")
    suspend fun getServers(): List<SourceEntity>

    @Query("SELECT * FROM sources WHERE type NOT IN $NOT_SERVER_TYPES ORDER BY priority DESC, name")
    fun observeServers(): Flow<List<SourceEntity>>

    @Query("UPDATE sources SET syncGeneration = :generation, lastFullSyncAt = :syncedAt WHERE sourceId = :sourceId")
    suspend fun setSyncGeneration(sourceId: String, generation: Long, syncedAt: Long)

    @Query("UPDATE sources SET enabled = :enabled WHERE sourceId = :sourceId")
    suspend fun setEnabled(sourceId: String, enabled: Boolean)

    /** Also removes all `song_sources` via CASCADE; call [LibraryDao.cleanupOrphans] afterwards. */
    @Query("DELETE FROM sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}
