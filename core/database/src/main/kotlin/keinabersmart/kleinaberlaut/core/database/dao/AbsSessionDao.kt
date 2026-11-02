package keinabersmart.kleinaberlaut.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import keinabersmart.kleinaberlaut.core.database.entity.AbsSessionEntity

/** Hörsitzungen, die Audiobookshelf gemeldet werden (siehe [AbsSessionEntity]). */
@Dao
abstract class AbsSessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsert(session: AbsSessionEntity)

    @Query("SELECT * FROM abs_sessions WHERE sessionId = :sessionId")
    abstract suspend fun session(sessionId: String): AbsSessionEntity?

    @Query("SELECT * FROM abs_sessions WHERE dirty = 1 ORDER BY updatedAt")
    abstract suspend fun dirty(): List<AbsSessionEntity>

    /** Sitzung als gemeldet markieren – nur, wenn sie sich seit dem Senden nicht geändert hat. */
    @Query("UPDATE abs_sessions SET dirty = 0 WHERE sessionId = :sessionId AND updatedAt = :updatedAt")
    abstract suspend fun markClean(sessionId: String, updatedAt: Long)

    @Query("DELETE FROM abs_sessions WHERE dirty = 0 AND updatedAt < :before")
    abstract suspend fun deleteCleanBefore(before: Long)
}
