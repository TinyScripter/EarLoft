package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import keinabersmart.kleinaberlaut.core.database.entity.WearSelectionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WearDao {
    /** In der Reihenfolge des Hinzufügens (bestimmt die Download-Reihenfolge auf der Uhr). */
    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    fun observeSelections(): Flow<List<WearSelectionEntity>>

    @Query("SELECT * FROM wear_selections ORDER BY addedAt, `key`")
    suspend fun getSelections(): List<WearSelectionEntity>

    @Upsert
    suspend fun upsert(selection: WearSelectionEntity)

    @Query("DELETE FROM wear_selections WHERE `key` = :key")
    suspend fun delete(key: String): Int

    @Query("DELETE FROM wear_selections")
    suspend fun deleteAll()
}
