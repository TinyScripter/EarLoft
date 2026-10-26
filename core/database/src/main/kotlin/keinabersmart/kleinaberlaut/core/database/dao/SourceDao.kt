package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import keinabersmart.kleinaberlaut.core.database.entity.SourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceDao {
    @Upsert
    suspend fun upsert(source: SourceEntity)

    @Query("SELECT * FROM sources ORDER BY priority DESC, name")
    fun observeAll(): Flow<List<SourceEntity>>

    @Query("SELECT * FROM sources WHERE sourceId = :sourceId")
    suspend fun get(sourceId: String): SourceEntity?

    /** Entfernt per CASCADE auch alle `song_sources`; danach [LibraryDao.cleanupOrphans] aufrufen. */
    @Query("DELETE FROM sources WHERE sourceId = :sourceId")
    suspend fun delete(sourceId: String)
}
